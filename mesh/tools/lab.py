#!/usr/bin/env python3
"""Reproducible process-level TCP overlay experiment in isolated Linux namespaces."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import tempfile
import time

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('mesh_test_support',ROOT/'tests/test_lab.py')
support=importlib.util.module_from_spec(spec);spec.loader.exec_module(support)

def command(*args):
    return subprocess.run(args,check=True,text=True,capture_output=True).stdout.strip()

def sample(node):
    data={}
    for line in Path(f'/proc/{node.p.pid}/status').read_text().splitlines():
        if line.startswith(('VmRSS:','VmHWM:')):
            field,value,*_=line.split(); data[field.rstrip(':')+'_KiB']=int(value)
    stat=Path(f'/proc/{node.p.pid}/stat').read_text().split()
    data['cpu_seconds']=(int(stat[13])+int(stat[14]))/os.sysconf('SC_CLK_TCK')
    return data

def topology():
    mapping=Path('/proc/self/uid_map').read_text().split()
    if os.getuid()!=0 or len(mapping)!=3 or mapping[2]!='1':
        raise RuntimeError('inside mode requires a single-user unshare namespace')
    if str(os.stat('/proc/self/ns/mnt').st_ino)==os.environ.get('MESH_LAB_PARENT_MNT') or 'MESH_LAB_PARENT_MNT' not in os.environ:
        raise RuntimeError('refusing mounts without an isolated mount namespace')
    command('mount','--make-rprivate','/')
    command('mount','-t','tmpfs','tmpfs','/run')
    Path('/run/netns').mkdir()
    for name in 'abc':
        command('ip','netns','add',name); command('ip','-n',name,'link','set','lo','up')
    for first,second,net in [('a','b',1),('b','c',2)]:
        left,right=first+second,second+first
        command('ip','link','add',left,'type','veth','peer','name',right)
        for name,iface,host in [(first,left,1),(second,right,2)]:
            command('ip','link','set',iface,'netns',name)
            command('ip','-n',name,'addr','add',f'10.77.{net}.{host}/30','dev',iface)
            command('ip','-n',name,'link','set',iface,'up')
    command('ip','netns','exec','b','sysctl','-w','net.ipv4.ip_forward=0')
    forwarding=command('ip','netns','exec','b','cat','/proc/sys/net/ipv4/ip_forward')
    if forwarding!='0': raise AssertionError('B kernel forwarding must be OFF')
    routes={name:command('ip','-n',name,'route','show') for name in 'abc'}
    checks=[]
    for source,target in [('a','10.77.2.2'),('c','10.77.1.1')]:
        code='import socket,json; s=socket.socket(); s.settimeout(1); print(json.dumps({"connect_ex":s.connect_ex(("'+target+'",39770))}))'
        result=json.loads(command('ip','netns','exec',source,sys.executable,'-c',code))
        if result['connect_ex']==0: raise AssertionError('A/C isolation failed')
        checks.append(dict(source=source,target=target,**result))
    return dict(routes=routes,kernel_forwarding=forwarding,negative_direct_connect=checks)

def run(args):
    evidence={'schema':1,'validation':'isolated TCP overlay, NOT wireless mesh','hardware_validation':'pending',
              'platform':platform.platform(),'python':platform.python_version(),'binary':str(support.BINARY),
              'topology':topology(),'checks':{},'latency_ms':[],'discovery':args.discovery}
    nodes=[]
    start=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='nearby-mesh-lab-') as temp:
        def node(name,relay=False,peers=()):
            n=support.Node(Path(temp)/name,peers=(() if args.discovery else peers),relay=relay,discover=args.discovery,prefix=['ip','netns','exec',name],listen='0.0.0.0:39770')
            nodes.append(n);return n
        try:
            b=node('b',True);a=node('a',peers=['10.77.1.2:39770']);c=node('c',peers=['10.77.2.1:39770'])
            a.wait('neighbor_up',timeout=20);c.wait('neighbor_up',timeout=20)
            evidence['pids']={'a':a.p.pid,'b':b.p.pid,'c':c.p.pid}
            keys={a.key:'A',b.key:'B',c.key:'C'}
            paths=[]
            for index in range(6):
                for src,dst in [(a,c),(c,a)]:
                    began=time.monotonic();src.command('send',text=f'lab-{index}',ttl=30)
                    mid=src.wait('queued')['id'];got=dst.wait('message',predicate=lambda e:e['id']==mid)
                    evidence['latency_ms'].append((time.monotonic()-began)*1000)
                    path=[keys[k] for k in got['path']];expected=['A','B','C'] if src is a else ['C','B','A']
                    assert path==expected,path;paths.append(path)
            evidence['checks']['bidirectional_paths']=paths
            b.command('relay',enabled=False);b.wait('relay')
            a.command('send',text='must-expire',ttl=1);blocked=a.wait('queued')['id']
            a.wait('expired',predicate=lambda e:e['id']==blocked)
            assert not any(e.get('event')=='message' and e.get('id')==blocked for e in c.events)
            evidence['checks']['relay_off_blocks']=True
            b.command('relay',enabled=True);b.wait('relay')
            # Break the second physical lab link. Pending data must recover without inventing a route.
            command('ip','-n','b','link','set','bc','down')
            a.command('send',text='partition',ttl=30);partition=a.wait('queued')['id']
            time.sleep(.3)
            assert not any(e.get('event')=='message' and e.get('id')==partition for e in c.events)
            command('ip','-n','b','link','set','bc','up')
            c.wait('message',timeout=20,predicate=lambda e:e['id']==partition)
            evidence['checks']['partition_recovery']=True
            # Restart B with the same identity and listen addresses; new traffic must traverse it.
            b.command('diagnostics');before=b.wait('diagnostics');before['proc']=sample(b);evidence['relay_before_restart']=before
            bkey=b.key;b.close();a.wait('neighbor_down');c.wait('neighbor_down')
            b=node('b',True);assert b.key==bkey
            a.wait('neighbor_up',timeout=12);c.wait('neighbor_up',timeout=12)
            c.command('send',text='after restart',ttl=30);mid=c.wait('queued')['id'];a.wait('message',predicate=lambda e:e['id']==mid)
            evidence['checks']['relay_restart_reconnect']=True
            evidence['nodes']={}
            for label,n in [('A',a),('B',b),('C',c)]:
                n.command('diagnostics');diag=n.wait('diagnostics');diag['proc']=sample(n);evidence['nodes'][label]=diag
                messages=[e['id'] for e in n.events if e.get('event')=='message']
                assert len(messages)==len(set(messages))
            evidence['checks']['no_duplicate_display']=True
            evidence['elapsed_seconds']=time.monotonic()-start
            values=evidence['latency_ms'];evidence['latency_summary_ms']={'samples':len(values),'min':min(values),'median':statistics.median(values),'max':max(values)}
        finally:
            for n in reversed(nodes):n.close()
    output=Path(args.output).resolve();output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    print(json.dumps({'evidence':str(output),'checks':evidence['checks'],'latency_summary_ms':evidence['latency_summary_ms']},ensure_ascii=False))

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',default=str(ROOT/'target/isolated-lab.json'))
    parser.add_argument('--discovery',action='store_true',help='Use opt-in DNS-SD instead of configured peers')
    parser.add_argument('--inside',action='store_true',help=argparse.SUPPRESS)
    args=parser.parse_args()
    if not args.inside:
        os.environ['MESH_LAB_PARENT_MNT']=str(os.stat('/proc/self/ns/mnt').st_ino)
        raise SystemExit(subprocess.call(['unshare','--user','--map-root-user','--mount','--net',sys.executable,str(Path(__file__).resolve()),'--inside','--output',str(Path(args.output).resolve()),*(['--discovery'] if args.discovery else [])]))
    run(args)
