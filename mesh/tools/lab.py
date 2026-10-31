#!/usr/bin/env python3
"""Reproducible process-level TCP overlay experiment in isolated Linux namespaces."""
import argparse
from datetime import datetime, timezone
import errno
import hashlib
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

def process_sample(pid):
    data={'pid':pid}
    for line in Path(f'/proc/{pid}/status').read_text().splitlines():
        if line.startswith(('VmRSS:','VmHWM:')):
            field,value,*_=line.split(); data[field.rstrip(':')+'_KiB']=int(value)
    # comm may contain spaces or ')'; fields after the final ')' have stable offsets.
    stat=Path(f'/proc/{pid}/stat').read_text().rsplit(')',1)[1].split()
    data['start_time_ticks']=int(stat[19])
    data['cpu_ticks']=int(stat[11])+int(stat[12])
    data['clock_ticks_per_second']=os.sysconf('SC_CLK_TCK')
    data['cpu_seconds']=data['cpu_ticks']/data['clock_ticks_per_second']
    data['Pss_KiB']=None
    try:
        for line in Path(f'/proc/{pid}/smaps_rollup').read_text().splitlines():
            if line.startswith('Pss:'): data['Pss_KiB']=int(line.split()[1])
    except OSError:
        pass
    return data

def sample(node):
    """Keep parent measurements and include live discovery descendants at each sample point."""
    parent=process_sample(node.p.pid)
    processes=[parent];pending=[node.p.pid];seen={node.p.pid};unavailable=[]
    # Some kernels omit /proc/PID/task/TID/children; PPid remains available in stat.
    children_by_parent={}
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit(): continue
        try:
            stat=(directory/'stat').read_text().rsplit(')',1)[1].split()
            children_by_parent.setdefault(int(stat[1]),[]).append((int(directory.name),int(stat[19])))
        except (OSError,ValueError,IndexError):
            continue
    while pending:
        pid=pending.pop()
        for child,start_time in children_by_parent.get(pid,[]):
            if child in seen: continue
            seen.add(child)
            try: process=process_sample(child)
            except OSError:
                unavailable.append(child);continue
            if process['start_time_ticks']!=start_time:
                unavailable.append(child);continue
            processes.append(process);pending.append(child)
    complete_pss=all(process['Pss_KiB'] is not None for process in processes)
    tree={'method':'sequential_procfs_ppid_point_sample','sampled_at_monotonic_ns':time.monotonic_ns(),
          'processes':processes,'process_count':len(processes),'unavailable_pids':unavailable,
          'RSS_total_KiB':sum(process.get('VmRSS_KiB',0) for process in processes),
          'PSS_total_KiB':sum(process['Pss_KiB'] for process in processes) if complete_pss else None,
          'cpu_ticks_total':sum(process['cpu_ticks'] for process in processes)}
    return dict(parent,process_tree=tree)

def binary_metadata():
    binary=support.BINARY.read_bytes()
    return {'binary_sha256':hashlib.sha256(binary).hexdigest(),'binary_bytes':len(binary)}

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
    return dict(routes=routes,kernel_forwarding=forwarding)

def isolation():
    """Probe live listeners, then require a routing failure in both excluded directions."""
    def connect(source,target):
        code='import socket,json,sys,errno\nwith socket.socket() as sock:\n sock.settimeout(1)\n result=sock.connect_ex((sys.argv[1],39770))\n print(json.dumps({"connect_ex":result,"errno":errno.errorcode.get(result,"OK")}))'
        result=json.loads(command('ip','netns','exec',source,sys.executable,'-c',code,target))
        return dict(source=source,target=target,**result)
    controls=[]
    for target in ['10.77.1.1','10.77.2.2']:
        result=connect('b',target)
        if result['connect_ex']!=0: raise AssertionError(f'live listener control failed: {result}')
        controls.append(result)
    checks=[]
    for source,target in [('a','10.77.2.2'),('c','10.77.1.1')]:
        result=connect(source,target)
        if result['connect_ex'] not in {errno.ENETUNREACH,errno.EHOSTUNREACH}:
            raise AssertionError(f'A/C routing isolation not established: {result}')
        checks.append(result)
    return dict(listener_controls=controls,negative_direct_connect=checks)

def run(args):
    artifact=binary_metadata()
    evidence={'schema':2,'validation':'isolated TCP overlay, NOT wireless mesh','hardware_validation':'pending',
              'platform':platform.platform(),'python':platform.python_version(),'binary':str(support.BINARY),
              **artifact,'started_at_utc':datetime.now(timezone.utc).isoformat(),
              'driver_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'source_snapshot_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT.parent,text=True).strip(),
              'source_snapshot_dirty':bool(subprocess.check_output(['git','status','--porcelain','--untracked-files=normal'],cwd=ROOT.parent,text=True).strip()),
              'binary_source_binding':'not_attested_by_this_tool',
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
            evidence['topology'].update(isolation())
            evidence['pids']={'a':a.p.pid,'b':b.p.pid,'c':c.p.pid}
            generations={'A':[a],'B':[b],'C':[c]}
            keys={a.key:'A',b.key:'B',c.key:'C'}
            paths=[]; sent_ids=[]
            for index in range(6):
                for src,dst in [(a,c),(c,a)]:
                    began=time.monotonic();src.command('send',text=f'lab-{index}',ttl=30)
                    mid=src.wait('queued')['id'];got=dst.wait('message',predicate=lambda e:e['id']==mid)
                    sent_ids.append(mid)
                    evidence['latency_ms'].append((time.monotonic()-began)*1000)
                    path=[keys[k] for k in got['path']];expected=['A','B','C'] if src is a else ['C','B','A']
                    assert path==expected,path;paths.append(path)
            evidence['checks']['bidirectional_paths']=paths
            b.command('relay',enabled=False);b.wait('relay')
            a.command('send',text='must-expire',ttl=1);blocked=a.wait('queued')['id']
            b.wait('message',predicate=lambda e:e['id']==blocked)
            c.assert_absent('message',timeout=1.2,predicate=lambda e:e['id']==blocked)
            a.wait('expired',predicate=lambda e:e['id']==blocked)
            c.barrier();c.assert_absent('message',timeout=0,predicate=lambda e:e['id']==blocked)
            evidence['checks']['relay_off_blocks']=True
            b.command('relay',enabled=True);b.wait('relay')
            # Close the endpoint while the link is up so B observes a real disconnect.
            # Restart it behind the broken link; recovery must now reoffer B's pending packet.
            ckey=c.key;disconnected=b.mark();c.close()
            b.wait('neighbor_down',after=disconnected,predicate=lambda e:e['peer']==ckey)
            command('ip','-n','b','link','set','bc','down')
            c=node('c',peers=['10.77.2.1:39770']);assert c.key==ckey
            generations['C'].append(c)
            a.command('send',text='partition',ttl=30);partition=a.wait('queued')['id']
            b.wait('message',predicate=lambda e:e['id']==partition)
            c.assert_absent('message',timeout=.3,predicate=lambda e:e['id']==partition)
            command('ip','-n','b','link','set','bc','up')
            c.wait('neighbor_up',timeout=20,predicate=lambda e:e['peer']==b.key)
            c.wait('message',timeout=20,predicate=lambda e:e['id']==partition)
            evidence['checks']['partition_recovery']=True
            # Restart B with the same identity and listen addresses; new traffic must traverse it.
            b.command('diagnostics');before=b.wait('diagnostics');before['proc']=sample(b);evidence['relay_before_restart']=before
            bkey=b.key;adisconnected=a.mark();cdisconnected=c.mark();b.close()
            a.wait('neighbor_down',after=adisconnected,predicate=lambda e:e['peer']==bkey)
            c.wait('neighbor_down',after=cdisconnected,predicate=lambda e:e['peer']==bkey)
            areconnected=a.mark();creconnected=c.mark()
            b=node('b',True);assert b.key==bkey
            generations['B'].append(b)
            a.wait('neighbor_up',timeout=12,after=areconnected,predicate=lambda e:e['peer']==bkey)
            c.wait('neighbor_up',timeout=12,after=creconnected,predicate=lambda e:e['peer']==bkey)
            b.wait('duplicate',timeout=12,predicate=lambda e:e['id']==sent_ids[0])
            c.command('send',text='after restart',ttl=30);mid=c.wait('queued')['id'];a.wait('message',predicate=lambda e:e['id']==mid)
            evidence['checks']['relay_restart_reconnect']=True
            evidence['checks']['restart_replay_rejected']=True
            evidence['nodes']={}
            for label,n in [('A',a),('B',b),('C',c)]:
                n.command('diagnostics');diag=n.wait('diagnostics');diag['proc']=sample(n);evidence['nodes'][label]=diag
                n.barrier()
                messages=[e['id'] for generation in generations[label] for e in generation.snapshot() if e.get('event')=='message']
                assert len(messages)==len(set(messages))
            evidence['process_generations']={label:[n.p.pid for n in history] for label,history in generations.items()}
            evidence['checks']['no_duplicate_display']=True
            evidence['elapsed_seconds']=time.monotonic()-start
            values=evidence['latency_ms'];evidence['latency_summary_ms']={'samples':len(values),'min':min(values),'median':statistics.median(values),'max':max(values)}
        finally:
            for n in reversed(nodes):n.close()
    if binary_metadata()!=artifact:
        raise RuntimeError('binary changed during topology run; refusing mixed-artifact evidence')
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
