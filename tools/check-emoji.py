#!/usr/bin/env python3
"""Offline integrity gates for the shipped Unicode catalog (no source downloads)."""
import gzip,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=(root/'i18n/emoji/catalog.tsv.gz').read_bytes()
for p in ['app/src/main/res/raw/emoji_catalog.gz','desktop/src/main/resources/dev/ghost/wozai/emoji_catalog.gz']:
 assert (root/p).read_bytes()==source,p+' drift'
rows=[line.split('\t') for line in gzip.decompress(source).decode().splitlines()]
assert len(rows)==3781
assert len({r[0] for r in rows})==len(rows)
for row in rows:
 assert len(row)==14 and all(row[i] for i in [0,1,2,3,4,6,8,10,12])
license=(root/'i18n/emoji/LICENSE.txt').read_bytes()
for p in ['app/src/main/res/raw/emoji_license.txt','desktop/src/main/resources/dev/ghost/wozai/emoji-license.txt']:
 assert (root/p).read_bytes()==license,p+' license drift'
print('Emoji catalog: 3781 unique complete sequences, five labels each, identical platform data/licenses; '+str(len(source))+' bytes compressed')
