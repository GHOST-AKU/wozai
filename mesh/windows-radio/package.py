"""Package a source-bound Windows lab, without bundling a Python runtime or USB driver."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
exe = root / 'mesh/target/release/mesh-lab.exe'
if not exe.is_file():
    raise SystemExit('Native Windows binary missing')
if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root).strip():
    raise SystemExit('Refusing to package a dirty tree')
commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
files = [exe, *[root / 'mesh/windows-radio' / name for name in
               ['nearby_coc.py', 'requirements.txt', 'run-core.ps1', 'README.md', 'THIRD_PARTY.md']],
         root / 'LICENSE', root / 'mesh/THIRD_PARTY.md', *sorted((root / 'mesh/vendor-licenses').rglob('*'))]
files = [p for p in files if p.is_file()]
manifest = {'commit': commit, 'hardware_validation': 'pending', 'authenticode_signed': False,
            'source_url': 'https://github.com/GHOST-AKU/wozai/tree/' + commit,
            'files': {str(p.relative_to(root)): {'sha256': hashlib.sha256(p.read_bytes()).hexdigest(), 'bytes': p.stat().st_size} for p in files}}
with zipfile.ZipFile(root / 'mesh/target/windows-mesh-lab.zip', 'w', zipfile.ZIP_DEFLATED) as out:
    for path in files:
        if path == exe:
            name = 'mesh-lab.exe'
        elif path.parent == root / 'mesh/windows-radio':
            name = path.name
        else:
            name = str(path.relative_to(root))
        out.write(path, 'windows-mesh-lab/' + name)
    out.writestr('windows-mesh-lab/build-info.json', json.dumps(manifest, indent=2))
print(json.dumps({'commit': commit, 'binary_bytes': exe.stat().st_size, 'hardware_validation': 'pending'}))
