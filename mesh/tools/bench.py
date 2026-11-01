#!/usr/bin/env python3
"""Compare headless TCP nodes with bounded traffic and observed process resources."""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import threading
import time

class Node:
    def __init__(self, binary, state, peers=(), relay=False):
        args=[str(binary),'run','--state',str(state),'--listen','127.0.0.1:0']
        for peer in peers:args+=['--peer',peer]
        if relay:args+=['--relay']
        self.p=subprocess.Popen(args,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,bufsize=1)
        self.condition=threading.Condition();self.events=[];self.consumed=set()
        def reader():
            for line in self.p.stdout:
                event=json.loads(line)
                with self.condition:
                    self.events.append((time.monotonic_ns(),event));self.condition.notify_all()
            with self.condition:self.condition.notify_all()
        self.thread=threading.Thread(target=reader,daemon=True);self.thread.start()
        self.ready=self.wait('ready')[1];self.address=self.ready['address']
    def send(self, text):
        started=time.monotonic_ns()
        self.p.stdin.write(json.dumps({'cmd':'send','text':text,'ttl':30})+'\n');self.p.stdin.flush()
        _,queued=self.wait('queued')
        return started,queued['id']
    def wait(self,event,mid=None,timeout=8):
        deadline=time.monotonic()+timeout
        with self.condition:
            while True:
                for index,(stamp,value) in enumerate(self.events):
                    if index not in self.consumed and value.get('event')==event and (mid is None or value.get('id')==mid):
                        self.consumed.add(index);return stamp,value
                if self.p.poll() is not None:raise RuntimeError(f'node exit {self.p.returncode}')
                remaining=deadline-time.monotonic()
                if remaining<=0:raise TimeoutError(event)
                self.condition.wait(min(remaining,.2))
    def close(self):
        if self.p.poll() is None:
            self.p.stdin.write('{"cmd":"quit"}\n');self.p.stdin.flush()
            try:self.p.wait(timeout=3)
            except subprocess.TimeoutExpired:self.p.kill();self.p.wait()
        self.thread.join(timeout=1)
        self.p.stdin.close();self.p.stdout.close();self.p.stderr.close()

def sample(pid):
    result={}
    for line in Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith(('VmRSS:','VmHWM:')):
            name,value,*_=line.split();result[name.rstrip(':')+'_KiB']=int(value)
    for line in Path(f'/proc/{pid}/smaps_rollup').read_text().splitlines():
        if line.startswith('Pss:'):result['Pss_KiB']=int(line.split()[1])
    raw=Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
    result['cpu_ticks']=int(raw[11])+int(raw[12])
    return result

def trial(binary,body_bytes,quiet_seconds):
    nodes=[]
    with tempfile.TemporaryDirectory(prefix='mesh-perf-') as temp:
        try:
            b=Node(binary,Path(temp)/'b',relay=True);nodes.append(b)
            a=Node(binary,Path(temp)/'a',[b.address]);nodes.append(a)
            c=Node(binary,Path(temp)/'c',[b.address]);nodes.append(c)
            a.wait('neighbor_up');c.wait('neighbor_up')
            latency=[]
            if body_bytes:
                body='x'*body_bytes
                for index in range(14):
                    for source,destination in [(a,c),(c,a)]:
                        began,mid=source.send(body)
                        ended,event=destination.wait('message',mid)
                        assert event['body']==body and len(event['path'])==3
                        if index>=2:latency.append((ended-began)/1e6)
            ordered={'A':a,'B':b,'C':c}
            # Observe the retained, valid forwarding cache rather than just process startup.
            time.sleep(.05)
            before={name:sample(n.p.pid) for name,n in ordered.items()};began=time.monotonic()
            time.sleep(quiet_seconds)
            after={name:sample(n.p.pid) for name,n in ordered.items()};elapsed=time.monotonic()-began
            ticks=sum(after[name]['cpu_ticks']-before[name]['cpu_ticks'] for name in ordered)
            events={name:Counter(e['event'] for _,e in n.events) for name,n in ordered.items()}
            assert not any(counts['rejected'] or counts['error'] for counts in events.values()),events
            if body_bytes:
                assert events['A']['message']==14 and events['C']['message']==14 and events['B']['message']==28,events
            return {'body_bytes':body_bytes,'messages_per_origin':14 if body_bytes else 0,
                    'measured_messages':len(latency),'latency_ms':latency,
                    'latency_median_ms':statistics.median(latency) if latency else None,
                    'quiet_seconds':elapsed,'quiet_cpu_ticks':ticks,'quiet_cpu_seconds':ticks/os.sysconf('SC_CLK_TCK'),
                    'before':before,'after':after,'events':{k:dict(v) for k,v in events.items()}}
        finally:
            for n in reversed(nodes):n.close()

def main(args):
    if os.getuid()!=0 or str(os.stat('/proc/self/ns/net').st_ino)==os.environ.get('MESH_BENCH_PARENT_NET') or 'MESH_BENCH_PARENT_NET' not in os.environ:
        raise RuntimeError('requires the private user/network namespace launcher')
    subprocess.run(['ip','link','set','lo','up'],check=True)
    binary=Path(args.binary).resolve()
    evidence={'schema':1,'kind':'loopback TCP process benchmark, not wireless','hardware_validation':'pending',
              'binary_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'binary_bytes':binary.stat().st_size,
              'platform':platform.platform(),'clock_ticks_per_second':os.sysconf('SC_CLK_TCK'),
              'workload':'three processes; two warmups + 12 samples each direction; each origin 14/min; default relay/cache policies',
              'trials':[]}
    for body_bytes in [0,5,2048]:
        for index in range(args.trials):
            value=trial(binary,body_bytes,args.quiet_seconds);value['trial']=index+1;evidence['trials'].append(value)
    evidence['summary']={}
    for size in [0,5,2048]:
        values=[t for t in evidence['trials'] if t['body_bytes']==size]
        evidence['summary'][str(size)]={'quiet_cpu_median_seconds':statistics.median(v['quiet_cpu_seconds'] for v in values),
            'latency_median_ms':statistics.median(v['latency_median_ms'] for v in values) if size else None,
            'max_parent_HWM_KiB':max(p['VmHWM_KiB'] for v in values for p in v['after'].values())}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(evidence,indent=2)+'\n')
    print(json.dumps({'output':str(args.output),'binary_sha256':evidence['binary_sha256'],'summary':evidence['summary']},indent=2))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary',required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--trials',type=int,default=3)
    parser.add_argument('--quiet-seconds',type=float,default=1.5)
    parser.add_argument('--inside',action='store_true',help=argparse.SUPPRESS)
    args=parser.parse_args()
    if not 1<=args.trials<=5 or not .5<=args.quiet_seconds<=10:parser.error('trials 1..5; quiet-seconds .5..10')
    if not args.inside:
        os.environ['MESH_BENCH_PARENT_NET']=str(os.stat('/proc/self/ns/net').st_ino)
        raise SystemExit(subprocess.call(['unshare','--user','--map-root-user','--net',sys.executable,str(Path(__file__).resolve()),
            '--inside','--binary',str(Path(args.binary).resolve()),'--output',str(args.output.resolve()),
            '--trials',str(args.trials),'--quiet-seconds',str(args.quiet_seconds)]))
    main(args)
