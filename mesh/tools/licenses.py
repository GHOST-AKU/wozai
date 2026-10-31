#!/usr/bin/env python3
"""Collect notices for the exact Cargo lock graph, including build/dev/target-only crates."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--metadata',type=Path,help='Existing cargo metadata JSON; otherwise run cargo metadata --locked')
args=parser.parse_args()
metadata=json.loads(args.metadata.read_text() if args.metadata else subprocess.check_output(['cargo','metadata','--manifest-path',str(ROOT/'Cargo.toml'),'--locked','--format-version','1'],text=True))
output=ROOT/'vendor-licenses';output.mkdir(exist_ok=True)
records=[]
for package in sorted(metadata['packages'],key=lambda p:(p['name'],p['version'])):
    if not package['source']:continue
    folder=Path(package['manifest_path']).parent
    target=output/f"{package['name']}-{package['version']}";target.mkdir(exist_ok=True)
    files=[]
    for source in folder.iterdir():
        if source.is_file() and source.name.lower().startswith(('license','copying','notice','copyright','authors')):
            shutil.copyfile(source,target/source.name);files.append(source.name)
    if not files:
        # These target-only upstream packages omit texts in the crate archive.
        # The adjacent manually sourced notices record their exact original URL.
        existing=[p.name for p in target.iterdir() if p.is_file() and p.name!='SOURCE.json']
        if not existing:raise SystemExit(f"Missing upstream license texts for {package['name']} {package['version']}")
        files=existing
    records.append({'name':package['name'],'version':package['version'],'license':package['license'],
                    'source':f"https://crates.io/crates/{package['name']}/{package['version']}",
                    'repository':package.get('repository'),'notices':[f"vendor-licenses/{target.name}/{name}" for name in sorted(files)]})
(output/'sources.json').write_text(json.dumps(records,indent=2)+'\n')
rows=['# Third-party dependency notices','',
      'The module itself follows the repository AGPL-3.0-only license. No implementation from the mesh systems researched in docs/mesh was copied here.',
      'This generated inventory covers the locked Cargo graph, including optional target, build and test dependencies. It is not a claim that every crate is linked into the Linux executable.',
      'Original license and copyright notices are retained in `vendor-licenses/`. Dependencies keep their upstream licenses. Regenerate using `python3 tools/licenses.py` after changing Cargo.lock.','',
      '| Crate | Version | Upstream license expression | Notices |','| --- | --- | --- | --- |']
for r in records:
    rows.append(f"| [{r['name']}]({r['source']}) | {r['version']} | {r['license']} | [texts](vendor-licenses/{r['name']}-{r['version']}/) |")
(ROOT/'THIRD_PARTY.md').write_text('\n'.join(rows)+'\n')
print(f"Preserved license notices for {len(records)} locked dependency packages")
