"""Real-process tests. Removing validation, persistence or reconnect breaks these."""
import json
import os
from pathlib import Path
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest

BINARY = Path(os.environ.get('MESH_BINARY', Path(__file__).resolve().parents[1] / 'target/debug/mesh-lab')).resolve()

class Node:
    def __init__(self, state, peers=(), relay=False, prefix=(), listen="127.0.0.1:0", discover=False):
        args = [*prefix,str(BINARY),'run','--state',str(state),'--listen',listen]
        for peer in peers: args += ['--peer',peer]
        if relay: args += ['--relay']
        if discover: args += ['--discover']
        self.p = subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        self.events=[]; self._pending=[]; self._condition=threading.Condition()
        self._reader_error=None; self._output_closed=False
        def reader():
            try:
                for line in self.p.stdout:
                    event=json.loads(line)
                    with self._condition:
                        self._pending.append((len(self.events),event))
                        self.events.append(event); self._condition.notify_all()
            except (ValueError,OSError) as error:
                with self._condition:
                    self._reader_error=str(error)
            finally:
                with self._condition:
                    self._output_closed=True; self._condition.notify_all()
        self.thread=threading.Thread(target=reader,daemon=True); self.thread.start()
        self.ready=self.wait('ready'); self.address=self.ready['address']; self.key=self.ready['key']
    def command(self,cmd,**kw):
        self.p.stdin.write(json.dumps(dict(cmd=cmd,**kw))+'\n'); self.p.stdin.flush()
    def _check_reader(self):
        if self._reader_error is not None:
            raise AssertionError(f'invalid node output: {self._reader_error}')
        if self.p.poll() is not None:
            raise AssertionError(f'node exited {self.p.returncode}: {self.p.stderr.read()}')
        if self._output_closed:
            raise AssertionError('node stdout closed before the expected event')
    def mark(self):
        with self._condition: return len(self.events)
    def snapshot(self):
        with self._condition: return list(self.events)
    def wait(self,event,timeout=8,predicate=lambda e:True,after=0):
        end=time.monotonic()+timeout
        with self._condition:
            while True:
                for index,(sequence,item) in enumerate(self._pending):
                    if sequence>=after and item.get('event')==event and predicate(item):
                        self._pending.pop(index); return item
                self._check_reader()
                remaining=end-time.monotonic()
                if remaining<=0:
                    raise AssertionError(f'missing {event}: {self.events[-8:]}')
                self._condition.wait(min(.2,remaining))
    def assert_absent(self,event,timeout=.2,predicate=lambda e:True,after=0):
        """Observe the full interval, including matching history, without consuming events."""
        end=time.monotonic()+timeout
        with self._condition:
            cursor=after
            while True:
                for item in self.events[cursor:]:
                    if item.get('event')==event and predicate(item):
                        raise AssertionError(f'unexpected {event}: {item}')
                cursor=len(self.events)
                self._check_reader()
                remaining=end-time.monotonic()
                if remaining<=0: return
                self._condition.wait(min(.2,remaining))
    def barrier(self,timeout=8):
        """Read through a fresh status response; this does not acknowledge network delivery."""
        after=self.mark(); self.command('status')
        return self.wait('status',timeout=timeout,after=after)
    def close(self):
        if self.p.poll() is None:
            self.command('quit')
            try: self.p.wait(timeout=3)
            except subprocess.TimeoutExpired: self.p.kill(); self.p.wait()
        self.thread.join(timeout=1)
        self.p.stdin.close(); self.p.stdout.close(); self.p.stderr.close()

class EventWaitTests(unittest.TestCase):
    """The driver must retain interleaved evidence instead of losing it in wait()."""
    SCRIPT = '''import json, sys, time
def emit(event, **fields):
    print(json.dumps(dict(event=event, **fields)), flush=True)
emit('ready', address='127.0.0.1:0', key='fixture')
for line in sys.stdin:
    command = json.loads(line)['cmd']
    if command == 'quit': break
    if command == 'interleave':
        emit('duplicate', id='replay')
        emit('status', phase='stale')
        emit('message', id='payload')
    if command == 'status': emit('status', phase='fresh')
    if command == 'delayed':
        time.sleep(.05)
        emit('message', id='delayed')
'''
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.node=Node(Path(self.temp.name),prefix=[sys.executable,'-u','-c',self.SCRIPT])
    def tearDown(self):
        self.node.close(); self.temp.cleanup()
    def test_wait_preserves_an_unrelated_earlier_event(self):
        self.node.command('interleave')
        self.assertEqual(self.node.wait('message')['id'],'payload')
        self.assertEqual(self.node.wait('duplicate',timeout=.2)['id'],'replay')
    def test_timeout_preserves_unmatched_evidence(self):
        self.node.command('interleave')
        with self.assertRaisesRegex(AssertionError,'missing absent'):
            self.node.wait('absent',timeout=.1)
        self.assertEqual(self.node.wait('message')['id'],'payload')
        self.assertEqual(self.node.wait('duplicate')['id'],'replay')
    def test_absence_observes_delayed_events_without_consuming_them(self):
        self.node.command('delayed')
        with self.assertRaisesRegex(AssertionError,'unexpected message'):
            self.node.assert_absent('message',timeout=.5)
        self.assertEqual(self.node.wait('message')['id'],'delayed')
    def test_barrier_requires_a_fresh_response_and_preserves_older_status(self):
        self.node.command('interleave'); self.node.wait('message')
        self.assertEqual(self.node.barrier()['phase'],'fresh')
        self.assertEqual(self.node.wait('status')['phase'],'stale')

class RuntimeTests(unittest.TestCase):
    def setUp(self): self.temp=tempfile.TemporaryDirectory(); self.nodes=[]
    def tearDown(self):
        for n in reversed(self.nodes): n.close()
        self.temp.cleanup()
    def node(self,name,**kw):
        n=Node(Path(self.temp.name)/name,**kw); self.nodes.append(n); return n
    def test_process_bidirectional_relay_toggle_and_diagnostics(self):
        b=self.node('b',relay=True); a=self.node('a',peers=[b.address]); c=self.node('c',peers=[b.address])
        a.wait('neighbor_up'); c.wait('neighbor_up')
        a.command('send',text='A to C'); mid=a.wait('queued')['id']
        got=c.wait('message'); self.assertEqual(got['id'],mid); self.assertEqual(got['path'],[a.key,b.key,c.key])
        c.command('send',text='C to A'); reverse=c.wait('queued')['id']
        self.assertEqual(a.wait('message',predicate=lambda e:e['id']==reverse)['path'],[c.key,b.key,a.key])
        b.command('relay',enabled=False); b.wait('relay')
        a.command('send',text='blocked',ttl=1); blocked=a.wait('queued')['id']
        b.wait('message',predicate=lambda e:e['id']==blocked)
        c.assert_absent('message',timeout=1.2,predicate=lambda e:e['id']==blocked)
        a.wait('expired',predicate=lambda e:e['id']==blocked)
        c.barrier(); c.assert_absent('message',timeout=0,predicate=lambda e:e['id']==blocked)
        b.command('diagnostics'); diag=b.wait('diagnostics')
        serialized=json.dumps(diag)
        for secret in ['A to C','C to A','blocked',a.key,b.key,c.key]: self.assertNotIn(secret,serialized)
        self.assertLessEqual(diag['pending'],64); self.assertLessEqual(diag['connections'],8)
    def test_reconnect_and_restart_preserve_identity_and_dedupe(self):
        b=self.node('b',relay=True); a=self.node('a',peers=[b.address]); a.wait('neighbor_up')
        a.command('send',text='before restart',ttl=30); mid=a.wait('queued')['id']
        b.wait('message',predicate=lambda e:e['id']==mid)
        previous=b; key=b.key; address=b.address
        disconnected=a.mark()
        b.p.kill(); b.p.wait(timeout=3); b.thread.join(timeout=1)
        a.wait('neighbor_down',after=disconnected,predicate=lambda e:e['peer']==key)
        reconnected=a.mark()
        # A still owns the unexpired packet and must really reoffer it after the crash.
        b=self.node('b',relay=True,listen=address); self.assertEqual(b.key,key)
        b.wait('failed_restart',predicate=lambda e:e['count']>=1)
        a.wait('neighbor_up',timeout=10,after=reconnected,predicate=lambda e:e['peer']==key)
        b.wait('duplicate',timeout=10,predicate=lambda e:e['id']==mid)
        b.assert_absent('message',timeout=.2,predicate=lambda e:e['id']==mid)
        self.assertGreaterEqual(b.barrier()['seen'],1)
        messages=previous.snapshot()+b.snapshot()
        self.assertEqual(sum(e.get('event')=='message' and e.get('id')==mid for e in messages),1)
        a.command('send',text='after restart',ttl=30); fresh=a.wait('queued')['id']
        b.wait('message',predicate=lambda e:e['id']==fresh)
    def test_expired_partition_queue_is_not_reoffered_after_reconnect(self):
        b=self.node('b'); a=self.node('a',peers=[b.address]); a.wait('neighbor_up')
        key=b.key; address=b.address; disconnected=a.mark(); b.close()
        a.wait('neighbor_down',after=disconnected,predicate=lambda e:e['peer']==key)
        a.command('send',text='expires offline',ttl=1); mid=a.wait('queued')['id']
        a.wait('expired',timeout=3,predicate=lambda e:e['id']==mid)
        after=a.mark()
        reconnected=a.mark(); b=self.node('b',listen=address); self.assertEqual(b.key,key)
        a.wait('neighbor_up',timeout=10,after=reconnected,predicate=lambda e:e['peer']==key)
        a.command('send',text='live control',ttl=30); live=a.wait('queued')['id']
        b.wait('message',predicate=lambda e:e['id']==live)
        b.assert_absent('message',timeout=.2,predicate=lambda e:e['id']==mid)
        a.barrier(); a.assert_absent('hop_queued',timeout=0,after=after,predicate=lambda e:e['id']==mid)
    def test_malicious_frames_connection_and_control_bounds(self):
        b=self.node('b'); host,port=b.address.rsplit(':',1)
        for wire in [struct.pack('>I',32769),struct.pack('>I',0),struct.pack('>I',2)+b'{}',struct.pack('>I',1)+b'\xff']:
            with socket.create_connection((host,int(port))) as s:
                s.sendall(wire); s.settimeout(2)
                while s.recv(4096): pass
        b.command('send',text='x'*2049); self.assertEqual(b.wait('error')['reason'],'body_bound')
        b.command('send',text='ok',ttl=1); mid=b.wait('queued')['id']; b.wait('expired',predicate=lambda e:e['id']==mid)
        b.command('status'); self.assertLessEqual(b.wait('status')['connections'],8)
    def test_simultaneous_dials_settle_without_repeated_churn(self):
        def port():
            with socket.socket() as sock:
                sock.bind(('127.0.0.1',0));return sock.getsockname()[1]
        pa,pb=port(),port()
        a=self.node('a',listen=f'127.0.0.1:{pa}',peers=[f'127.0.0.1:{pb}'])
        b=self.node('b',listen=f'127.0.0.1:{pb}',peers=[a.address])
        a.wait('neighbor_up'); b.wait('neighbor_up')
        time.sleep(2.8)
        for n in [a,b]:
            n.command('status'); self.assertEqual(n.wait('status')['connections'],1)
            self.assertFalse(any(e.get('event')=='neighbor_down' for e in n.events),n.events)
            self.assertLessEqual(sum(e.get('event')=='neighbor_up' for e in n.events),2,n.events)
    def test_cycle_displays_once_and_records_duplicate(self):
        a=self.node('a'); b=self.node('b',peers=[a.address],relay=True)
        c=self.node('c',peers=[a.address,b.address],relay=True)
        b.wait('neighbor_up'); c.wait('neighbor_up'); c.wait('neighbor_up')
        a.command('send',text='cyclic'); mid=a.wait('queued')['id']
        b.wait('message',predicate=lambda e:e['id']==mid); c.wait('message',predicate=lambda e:e['id']==mid)
        # Whichever indirect copy arrives second is the duplicate; either relay may observe it.
        end=time.monotonic()+8
        while not any(e.get('event')=='duplicate' and e.get('id')==mid for n in [b,c] for e in n.snapshot()):
            if time.monotonic()>=end:
                self.fail(f'missing cyclic duplicate: B={b.snapshot()[-8:]}, C={c.snapshot()[-8:]}')
            time.sleep(.01)
        for n in [a,b,c]:
            n.barrier()
            self.assertEqual(sum(e.get('event')=='message' and e.get('id')==mid for e in n.snapshot()),0 if n is a else 1)
    def test_reset_during_connection_setup_does_not_stop_node(self):
        b=self.node('b'); host,port=b.address.rsplit(':',1)
        for _ in range(64):
            with socket.create_connection((host,int(port))) as sock:
                sock.setsockopt(socket.SOL_SOCKET,socket.SO_LINGER,struct.pack('ii',1,0))
        b.command('status'); self.assertEqual(b.wait('status')['event'],'status')
    def test_invalid_neighbor_signature_cannot_establish_identity(self):
        b=self.node('b')
        attacker=subprocess.run([str(BINARY),'keygen','--state',str(Path(self.temp.name)/'attacker')],check=True,text=True,capture_output=True)
        key=json.loads(attacker.stdout)['key']; host,port=b.address.rsplit(':',1)
        with socket.create_connection((host,int(port))) as sock:
            sock.settimeout(2)
            def send(value):
                wire=json.dumps(value).encode();sock.sendall(struct.pack('>I',len(wire))+wire)
            send(dict(type='hello',version=1,key=key,challenge='00'*16))
            send(dict(type='auth',signature='00'*64))
            try:
                while sock.recv(4096): pass
            except ConnectionResetError: pass
        b.command('status');status=b.wait('status')
        self.assertEqual(status['connections'],0)
        self.assertFalse(any(e.get('event')=='neighbor_up' for e in b.events))
    def test_discovery_worker_rejects_zero_port(self):
        p=subprocess.run([str(BINARY),'discovery-worker','0'],text=True,capture_output=True,timeout=3)
        self.assertNotEqual(p.returncode,0)
        self.assertIn('discovery_port',p.stderr)
    def test_state_lock_prevents_concurrent_identity_use(self):
        b=self.node('b')
        p=subprocess.run([str(BINARY),'run','--state',str(Path(self.temp.name)/'b'),'--listen','127.0.0.1:0'],input='',text=True,capture_output=True,timeout=3)
        self.assertNotEqual(p.returncode,0); self.assertIn('locked',p.stderr)

if __name__=='__main__': unittest.main()
