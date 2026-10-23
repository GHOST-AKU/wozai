#!/usr/bin/env python3
"""Linux optimized-build baseline. Warm-cache PTY samples, never mislabeled cold starts."""
import fcntl
import json
import os
from pathlib import Path
import platform
import pty
import select
import statistics
import struct
import subprocess
import sys
import tempfile
import termios
import time

binary = str(Path(sys.argv[1]).resolve())
def percentile(samples, percent):
    return sorted(samples)[min(len(samples)-1, int((len(samples)-1)*percent+0.999))]
def drain(master, timeout):
    data = b""
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        if select.select([master], [], [], 0.02)[0]:
            data += os.read(master, 65536)
            if b"NearbyIM" in data and b"Terminal" in data:
                return data
    raise RuntimeError("TUI did not draw: " + repr(data[:1500]))
help_times=[]
for _ in range(10):
    start=time.perf_counter()
    subprocess.run([binary,"--help"],stdout=subprocess.DEVNULL,check=True)
    help_times.append((time.perf_counter()-start)*1000)
with tempfile.TemporaryDirectory(prefix="nearbyim-measure-") as directory:
    args=[binary,"--profile",directory+"/profile"]
    subprocess.run(args+["config","set","lan_listen","false"],stdout=subprocess.DEVNULL,check=True)
    tui_times=[]
    for _ in range(10):
        master,slave=pty.openpty()
        fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack("HHHH",24,80,0,0))
        start=time.perf_counter()
        process=subprocess.Popen(args+["--no-color"],stdin=slave,stdout=slave,stderr=slave,env={**os.environ,"TERM":"xterm-256color"})
        try:
            drain(master,5)
            tui_times.append((time.perf_counter()-start)*1000)
            os.write(master,b"\x03")
            process.wait(timeout=5)
        finally:
            if process.poll() is None: process.kill();process.wait()
            os.close(master);os.close(slave)
    master,slave=pty.openpty()
    fcntl.ioctl(slave,termios.TIOCSWINSZ,struct.pack("HHHH",24,80,0,0))
    process=subprocess.Popen(args+["--no-color"],stdin=slave,stdout=slave,stderr=slave,env={**os.environ,"TERM":"xterm-256color"})
    try:
        drain(master,5)
        time.sleep(0.2)
        def usage():
            status=Path(f"/proc/{process.pid}/status").read_text()
            rss=int(next(line.split()[1] for line in status.splitlines() if line.startswith("VmRSS:")))*1024
            fields=Path(f"/proc/{process.pid}/stat").read_text().split()
            ticks=int(fields[13])+int(fields[14])
            return rss,ticks
        rss0,ticks0=usage();start=time.monotonic();time.sleep(5);rss1,ticks1=usage();elapsed=time.monotonic()-start
        cpu=(ticks1-ticks0)/os.sysconf("SC_CLK_TCK")/elapsed*100
        os.write(master,b"\x03");process.wait(timeout=5)
    finally:
        if process.poll() is None: process.kill();process.wait()
        os.close(master);os.close(slave)
result={"schema":1,"environment":{"platform":platform.platform(),"machine":platform.machine(),"python":platform.python_version(),"terminal":"synthetic 80x24 PTY, xterm-256color","cache":"warm/uncontrolled OS cache; NOT cold start","profile":"created before sampling","lan_listening":False,"discovery":False},"executable_bytes":Path(binary).stat().st_size,"help_ms":{"samples":help_times,"median":statistics.median(help_times),"p95":percentile(help_times,.95)},"tui_first_draw_ms":{"samples":tui_times,"median":statistics.median(tui_times),"p95":percentile(tui_times,.95)},"tui_idle":{"rss_bytes":max(rss0,rss1),"cpu_one_core_percent":cpu,"sample_seconds":elapsed},"limits":"No Windows, GUI comparison, key-to-frame latency, true cold-cache, physical LAN or device performance acceptance."}
print(json.dumps(result,indent=2))
