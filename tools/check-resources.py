#!/usr/bin/env python3
"""Validate XML and app resource references without pretending to run AAPT."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
res = root / 'app/src/main/res'
known = set()
trees = []
for path in sorted(res.rglob('*')):
    if not path.is_file():
        continue
    kind = path.parent.name.split('-')[0]
    if kind != 'values':
        known.add((kind, path.stem))
    if path.suffix == '.xml':
        tree = ET.parse(path)
        trees.append((path, tree))
        if kind == 'values':
            for child in tree.getroot():
                if child.get('name'):
                    known.add((child.tag if child.tag != 'item' else child.get('type'), child.get('name')))
manifest = root / 'app/src/main/AndroidManifest.xml'
trees.append((manifest, ET.parse(manifest)))
errors = []
for path, tree in trees:
    for node in tree.iter():
        for value in list(node.attrib.values()) + [node.text or '']:
            for kind, name in re.findall(r'@([a-z_]+)/([A-Za-z0-9_.]+)', value):
                if (kind, name) not in known:
                    errors.append(f'{path.relative_to(root)}: unknown @{kind}/{name}')
for path in sorted((root / 'app/src/main/java').rglob('*.java')):
    for kind, name in re.findall(r'(?<![\w.])R\.([a-z_]+)\.([A-Za-z0-9_]+)', path.read_text()):
        if kind != 'id' and (kind, name) not in known:
            errors.append(f'{path.relative_to(root)}: unknown R.{kind}.{name}')
for error in errors:
    print(error)
print(f'XML/resources: {len(trees)} XML files, {len(errors)} missing references (not AAPT)')
raise SystemExit(bool(errors))
