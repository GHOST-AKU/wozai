#!/usr/bin/env python3
"""Upload unchanged, hash-pinned existing artifacts to the owner's release draft."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

repo = 'GHOST-AKU/wozai'
release_id = 428592809
metadata = Path('tools/release-0.3.3')
provenance = json.loads((metadata / 'RELEASE_PROVENANCE.json').read_text())
release = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/releases/{release_id}']))
assert release['draft'] and not release['prerelease']
assert release['tag_name'] == 'v0.3.3'
assert release['target_commitish'] == provenance['release_commit']
assert not release['assets'], 'Do not replace existing assets'
output = Path('release-upload')
output.mkdir()
for item in provenance['packages']:
    original_name = 'candidate.apk' if item['file'].endswith('.apk') else item['file']
    matches = list(Path('release-input').rglob(original_name))
    assert len(matches) == 1, (original_name, matches)
    source = matches[0]
    assert source.stat().st_size == item['bytes'], item['file']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == item['sha256'], item['file']
    shutil.copy2(source, output / item['file'])
assert len(list(output.iterdir())) == 9
for name in ['SHA256SUMS.txt', 'RELEASE_PROVENANCE.json', 'INSTALL.md', 'LICENSE.txt', 'THIRD_PARTY_NOTICES.md']:
    shutil.copy2(metadata / name, output / name)
subprocess.run(['gh', 'release', 'upload', 'v0.3.3', '--repo', repo, *map(str, sorted(output.iterdir()))], check=True)
assets = json.loads(subprocess.check_output(['gh', 'api', f'repos/{repo}/releases/{release_id}/assets']))
assert {asset['name'] for asset in assets} == {file.name for file in output.iterdir()}
for asset in assets:
    file = output / asset['name']
    assert asset['state'] == 'uploaded' and asset['size'] == file.stat().st_size
    assert asset['digest'] == 'sha256:' + hashlib.sha256(file.read_bytes()).hexdigest()
print('Verified 14 uploaded assets, including nine unchanged packages; release remains draft.')
