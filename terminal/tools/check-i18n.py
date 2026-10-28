#!/usr/bin/env python3
"""Offline message and application-diagnostic coverage gate, without runtime dependencies."""
import re,json,pathlib
out={}
for p in pathlib.Path('terminal/src').glob('*.rs'):
 if p.name in ['interop.rs','i18n.rs']:continue
 source=re.split(r'#\[cfg\(test\)\]\s*mod\s',p.read_text(encoding="utf-8"))[0]
 vals=[]
 for name in ['ensure','bail','anyhow']:
  for m in re.finditer(r'\b'+name+r'!\s*\(',source):
   i=m.end();level=0;start=i;comma=None
   while i<len(source):
    c=source[i]
    if c=='"':
     st=i;i+=1
     while i<len(source):
      if source[i]=='\\':i+=2
      elif source[i]=='"':i+=1;break
      else:i+=1
     if level==0 and ((name=='ensure' and comma is not None) or (name!='ensure' and comma is None)):
      vals.append(source[st+1:i-1]);break
     continue
    if c in '([{':level+=1
    elif c in ')]}':
     if level==0:break
     level-=1
    elif c==',' and level==0:comma=i
    elif not c.isspace() and name!='ensure' and level==0:start=i+1
    i+=1
 for m in re.finditer(r'(?:\.context\(|fail\(\s*\d+\s*,\s*)"((?:[^"\\]|\\.)*)"',source):vals.append(m.group(1))
 for value in vals:
  if value not in out:
   slug=re.sub('[^a-z0-9]+','_',value.lower()).strip('_')[:65]
   out[value]='error.'+p.stem+'.'+slug
catalog=json.loads(pathlib.Path('terminal/i18n/messages.json').read_text(encoding="utf-8"))
known={row['en'] for key,row in catalog.items() if key.startswith('error.')}
missing=set(out)-known
assert not missing, 'Uncataloged application errors: '+repr(sorted(missing))
for key,row in catalog.items():
 assert set(row)=={'en','zh-CN','zh-Hant','ja','ko'},key
 assert all(value.strip() for value in row.values()),key
 expected=set(re.findall(r'\{([^{}]*)\}',row['en']))
 for language,value in row.items():assert set(re.findall(r'\{([^{}]*)\}',value))==expected,(key,language)
print(f'Terminal i18n: {len(catalog)} semantic messages, five complete languages; {len(out)} application diagnostic templates covered')
