#!/usr/bin/env python3
"""Verify the checked-in platform exports against the supplied icon pack."""
import hashlib
import json
from pathlib import Path
import struct
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / 'desktop/assets/icons/source-pack.json').read_text())
archive = None
if len(sys.argv) > 1:
    supplied = Path(sys.argv[1])
    assert hashlib.sha256(supplied.read_bytes()).hexdigest() == manifest['archive_sha256'], 'Icon pack checksum mismatch'
    archive = zipfile.ZipFile(supplied)
for asset in manifest['assets']:
    data = (root / asset['path']).read_bytes()
    assert hashlib.sha256(data).hexdigest() == asset['sha256'], 'Modified formal export: ' + asset['path']
    if archive is not None:
        assert data == archive.read(asset['entry']), 'Different from supplied icon pack: ' + asset['path']
    if asset['path'].endswith('.png'):
        assert data[:8] == b'\x89PNG\r\n\x1a\n' and min(struct.unpack_from('>II', data, 16)) > 0, asset['path']
if archive is not None:
    archive.close()
print(f"Formal app icons: {len(manifest['assets'])} original exports verified (Android, Windows ICO, Linux hicolor)")
