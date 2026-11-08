"""Drop one real framed send, then prove reconnect reoffers the same unexpired ID."""
from contextlib import ExitStack
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest
from test_lab import Node

class DropOnceProxy:
    def __init__(self,target):
        self.target=target;self.stop=threading.Event();self.dropped=threading.Event()
        self.release_drop=threading.Event()
        self.listener=socket.socket();self.listener.bind(('127.0.0.1',0));self.listener.listen(4);self.listener.settimeout(.1)
        host,port=self.listener.getsockname();self.address=f'{host}:{port}'
        self.sockets=[];self.threads=[];self.lock=threading.Lock();self.error=None
        self.thread=threading.Thread(target=self.accept,daemon=True);self.thread.start()
    def accept(self):
        try:
            while not self.stop.is_set():
                try:client,_=self.listener.accept()
                except socket.timeout:continue
                try:backend=socket.create_connection(self.target,timeout=2)
                except OSError:
                    client.close();raise
                for sock in [client,backend]:sock.settimeout(.1)
                self.sockets.extend([client,backend])
                for source,dest,drop in [(client,backend,True),(backend,client,False)]:
                    thread=threading.Thread(target=self.forward,args=(source,dest,drop),daemon=True)
                    self.threads.append(thread);thread.start()
        except OSError:
            if not self.stop.is_set():self.error='proxy accept failed'
    def read(self,sock,count):
        data=bytearray()
        while len(data)<count and not self.stop.is_set():
            try:chunk=sock.recv(count-len(data))
            except socket.timeout:continue
            if not chunk:return None
            data.extend(chunk)
        return bytes(data) if len(data)==count else None
    def forward(self,source,dest,drop):
        try:
            while not self.stop.is_set():
                header=self.read(source,4)
                if header is None:return
                size=struct.unpack('>I',header)[0]
                if not 0<size<=32768:raise ValueError('fixture frame bound')
                body=self.read(source,size)
                if body is None:return
                with self.lock:
                    lose=drop and not self.dropped.is_set() and json.loads(body).get('type')=='data'
                    if lose:self.dropped.set()
                if lose:
                    # Keep both sockets alive until the test observes the first handoff.
                    while not self.stop.is_set() and not self.release_drop.wait(.1):
                        pass
                    return
                dest.sendall(header+body)
        except OSError:
            pass
        except (ValueError,KeyError) as error:
            self.error=str(error)
        finally:
            for sock in [source,dest]:
                try:sock.shutdown(socket.SHUT_RDWR)
                except OSError:pass
    def close(self):
        self.stop.set();self.release_drop.set();self.listener.close()
        # A backend connect has a two-second deadline; finish it before closing its sockets.
        self.thread.join(timeout=3)
        for sock in self.sockets:sock.close()
        for thread in self.threads:thread.join(timeout=2)
        assert not self.thread.is_alive() and not any(t.is_alive() for t in self.threads)

class PacketLossTests(unittest.TestCase):
    def test_handed_frame_lost_before_receiver_is_reoffered_after_reconnect(self):
        with tempfile.TemporaryDirectory() as temp, ExitStack() as cleanup:
            b=Node(Path(temp)/'b');cleanup.callback(b.close)
            host,port=b.address.rsplit(':',1);proxy=DropOnceProxy((host,int(port)))
            cleanup.callback(proxy.close)
            a=Node(Path(temp)/'a',peers=[proxy.address]);cleanup.callback(a.close)
            a.wait('neighbor_up',predicate=lambda e:e['peer']==b.key)
            b.wait('neighbor_up',predicate=lambda e:e['peer']==a.key)
            before=a.mark();a.command('send',text='lost once',ttl=30);mid=a.wait('queued')['id']
            self.assertTrue(proxy.dropped.wait(8),'proxy must drop a real data frame')
            a.wait('handed',after=before,predicate=lambda e:e['id']==mid)
            b.barrier();b.assert_absent('message',timeout=.1,predicate=lambda e:e['id']==mid)
            a.assert_absent('neighbor_down',timeout=0,after=before,predicate=lambda e:e['peer']==b.key)
            # The first successful socket handoff has now been observed without delivery.
            disconnected=a.mark();proxy.release_drop.set()
            a.wait('neighbor_down',after=disconnected,predicate=lambda e:e['peer']==b.key)
            a.wait('neighbor_up',after=disconnected,predicate=lambda e:e['peer']==b.key)
            delivered=b.wait('message',timeout=10,predicate=lambda e:e['id']==mid)
            self.assertEqual(delivered['body'],'lost once')
            b.barrier();b.assert_absent('duplicate',timeout=.1,predicate=lambda e:e['id']==mid)
            self.assertEqual(sum(e.get('event')=='message' and e.get('id')==mid for e in b.snapshot()),1)
            self.assertIsNone(proxy.error)

if __name__=='__main__':unittest.main()
