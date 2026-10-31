#!/usr/bin/env python3
"""Record source hashes and actual local binary/environment metadata, never benchmark estimates."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import subprocess

ROOT=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--binary',type=Path,default=ROOT/'mesh/target/release/mesh-lab')
parser.add_argument('--output',type=Path,default=ROOT/'mesh/target/source-manifest.json')
parser.add_argument('--build-profile',default='unverified',help='Caller-supplied build profile; this tool does not attest binary/source correspondence')
args=parser.parse_args()
files=[ROOT/'mesh/Cargo.toml',ROOT/'mesh/Cargo.lock',ROOT/'mesh/android-probe/AndroidManifest.xml']
for folder in ['mesh/src','mesh/tests','mesh/tools','mesh/android-probe/src','mesh/android-probe/tests']:
    files += [p for p in (ROOT/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts]
files += [ROOT/'mesh/android-probe/build.sh']
files += [path for path in [ROOT/'.github/workflows/mesh.yml',
          ROOT/'.cargo/config',ROOT/'.cargo/config.toml',ROOT/'rust-toolchain',ROOT/'rust-toolchain.toml',
          ROOT/'mesh/.cargo/config',ROOT/'mesh/.cargo/config.toml'] if path.is_file()]
sha=lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
cpu=next((line.split(':',1)[1].strip() for line in Path('/proc/cpuinfo').read_text().splitlines() if line.startswith('model name')),'unknown')
data={'schema':2,'source_snapshot_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
      'source_snapshot_dirty':bool(subprocess.check_output(['git','status','--porcelain','--untracked-files=normal'],cwd=ROOT,text=True).strip()),
      'captured_at_utc':datetime.now(timezone.utc).isoformat(),
      'platform':platform.platform(),'architecture':platform.machine(),'cpu_model':cpu,
      'binary_path':str(args.binary.resolve()),'binary_bytes':args.binary.stat().st_size,'binary_sha256':sha(args.binary),
      'binary_source_binding':'not_attested_by_this_tool','build_profile':args.build_profile,
      'rustc_at_capture':subprocess.check_output(['rustc','--version'],text=True).strip(),'sources':{str(p.relative_to(ROOT)):sha(p) for p in sorted(set(files))}}
for name in ['memory.max','cpu.max','cpuset.cpus.effective']:
    path=Path('/sys/fs/cgroup')/name
    if path.exists():data['cgroup_'+name]=path.read_text().strip()
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps(data,indent=2)+'\n')
print(json.dumps({k:v for k,v in data.items() if k!='sources'},indent=2))
