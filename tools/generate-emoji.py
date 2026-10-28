#!/usr/bin/env python3
"""Generate one compact, licensed Unicode 16 / CLDR 47 catalog for both UIs."""
import argparse, gzip, hashlib, json, re, urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--sources',type=Path,required=True);parser.add_argument('--check',action='store_true');args=parser.parse_args()
lock=json.loads((ROOT/'i18n/emoji/sources.json').read_text(encoding="utf-8"))
for name, source in lock.items():
 raw=(args.sources/name).read_bytes()
 if hashlib.sha256(raw).hexdigest()!=source['sha256']:raise SystemExit('Source hash mismatch: '+name)
langs=['en','zh','zh_Hant','ja','ko']; labels=[]
for lang in langs:
 entries={}
 for part in ['annotations','annotationsDerived']:
  for item in ET.parse(args.sources/f'{lang}-{part}.xml').iter('annotation'):
   seq=item.attrib['cp'].replace('\ufe0f','');slot=entries.setdefault(seq,{})
   slot['label' if item.get('type')=='tts' else 'keywords']=item.text or ''
 territories={e.get('type'):e.text for e in ET.parse(args.sources/f'{lang}-territories.xml').iter('territory')}
 territories.update({key: value[lang] for key,value in json.loads((ROOT/'i18n/emoji/supplements.json').read_text(encoding="utf-8")).items() if lang in value})
 if 'CQ' in territories:
  prefixes={'en':'flag: ','zh':'旗: ','zh_Hant':'旗: ','ja':'旗: ','ko':'깃발: '}
  entries['🇨🇶']={'label':prefixes[lang]+territories['CQ'],'keywords':territories['CQ']}
 labels.append(entries)
rows=[];category='';sub='';groups={};missing=[]
for line in (args.sources/'emoji-test.txt').read_text(encoding="utf-8").splitlines():
 if line.startswith('# group:'):category=line.split(': ',1)[1]
 if line.startswith('# subgroup:'):sub=line.split(': ',1)[1]
 if '; fully-qualified' not in line:continue
 points=line.split(';')[0].split();seq=''.join(chr(int(p,16)) for p in points)
 base=''.join(c for c in seq if not 0x1F3FB<=ord(c)<=0x1F3FF).replace('\ufe0f','')
 groups.setdefault(base,seq)
 fields=[seq,category,sub,base]
 for lang,entries in zip(langs,labels):
  v=entries.get(seq.replace('\ufe0f',''))
  if not v or not v.get('label'):missing.append((lang,seq))
  fields += [(v or {}).get('label',''),' | '.join((v or {}).get('keywords','').split(' | ')[:3])]
 rows.append('\t'.join(f.replace('\t',' ').replace('\n',' ') for f in fields))
if missing:raise SystemExit('Missing localized labels: '+repr(missing[:30]))
data=gzip.compress(('\n'.join(rows)+'\n').encode(),mtime=0)
for relative in ['i18n/emoji/catalog.tsv.gz','app/src/main/res/raw/emoji_catalog.gz','desktop/src/main/resources/dev/ghost/wozai/emoji_catalog.gz']:
 path=ROOT/relative
 if args.check:
  assert path.read_bytes()==data,relative+' drift'
 else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
print(f'{len(rows)} sequences; {len(data)} compressed bytes; Unicode16/CLDR47, five languages')
