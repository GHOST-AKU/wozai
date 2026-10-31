"""Real-process tests. Removing validation, persistence or reconnect breaks these."""
import json
import os
from pathlib import Path
import queue
import socket
import struct
import subprocess
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
        self.events=[]; self.q=queue.Queue()
        def reader():
            for line in self.p.stdout:
                event=json.loads(line); self.events.append(event); self.q.put(event)
        self.thread=threading.Thread(target=reader,daemon=True); self.thread.start()
        self.ready=self.wait('ready'); self.address=self.ready['address']; self.key=self.ready['key']
    def command(self,cmd,**kw):
        self.p.stdin.write(json.dumps(dict(cmd=cmd,**kw))+'\n'); self.p.stdin.flush()
    def wait(self,event,timeout=8,predicate=lambda e:True):
        end=time.monotonic()+timeout
        while time.monotonic()<end:
            try: e=self.q.get(timeout=min(.2,max(.01,end-time.monotonic())))
            except queue.Empty:
                if self.p.poll() is not None:
                    raise AssertionError(f'node exited {self.p.returncode}: {self.p.stderr.read()}')
                continue
            if e.get('event')==event and predicate(e): return e
        raise AssertionError(f'missing {event}: {self.events[-8:]}')
    def close(self):
        if self.p.poll() is None:
            self.command('quit')
            try: self.p.wait(timeout=3)
            except subprocess.TimeoutExpired: self.p.kill(); self.p.wait()
        self.thread.join(timeout=1)
        self.p.stdin.close(); self.p.stdout.close(); self.p.stderr.close()

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
        c.command('send',text='C to A'); a.wait('message')
        b.command('relay',enabled=False); b.wait('relay')
        a.command('send',text='blocked',ttl=1); blocked=a.wait('queued')['id']
        a.wait('expired',predicate=lambda e:e['id']==blocked)
        self.assertFalse(any(e.get('event')=='message' and e.get('id')==blocked for e in c.events))
        b.command('diagnostics'); diag=b.wait('diagnostics')
        serialized=json.dumps(diag)
        for secret in ['A to C','C to A','blocked',a.key,b.key,c.key]: self.assertNotIn(secret,serialized)
        self.assertLessEqual(diag['pending'],64); self.assertLessEqual(diag['connections'],8)
    def test_reconnect_and_restart_preserve_identity_and_dedupe(self):
        b=self.node('b',relay=True); a=self.node('a',peers=[b.address]); a.wait('neighbor_up')
        a.command('send',text='before restart'); a.wait('queued'); b.wait('message')
        key=b.key; address=b.address; b.close(); a.wait('neighbor_down')
        # The listener port is supplied explicitly to allow candidate reconnect.
        args=[str(BINARY),'run','--state',str(Path(self.temp.name)/'b'),'--listen',address,'--relay']
        probe=subprocess.run([str(BINARY),'keygen','--state',str(Path(self.temp.name)/'b')],text=True,capture_output=True,check=True)
        self.assertEqual(json.loads(probe.stdout)['key'],key)
        p=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            ready=json.loads(p.stdout.readline()); self.assertEqual(ready['key'],key)
            a.wait('neighbor_up',timeout=10)
            p.stdin.write('{"cmd":"status"}\n'); p.stdin.flush()
            end=time.monotonic()+4
            while time.monotonic()<end:
                e=json.loads(p.stdout.readline())
                self.assertNotEqual(e.get('event'),'message','unexpired replay displayed after restart')
                if e.get('event')=='status':
                    self.assertGreaterEqual(e['seen'],1); break
        finally:
            p.stdin.write('{"cmd":"quit"}\n'); p.stdin.flush(); p.wait(timeout=3)
            p.stdin.close();p.stdout.close();p.stderr.close()
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
        b.wait('duplicate',predicate=lambda e:e['id']==mid)
        for n in [a,b,c]:
            self.assertLessEqual(sum(e.get('event')=='message' and e.get('id')==mid for e in n.events),1)
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
