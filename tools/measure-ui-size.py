#!/usr/bin/env python3
"""Measure exact APKs or controlled desktop archives; never publish a baseline."""
import argparse,gzip,hashlib,io,json,os,platform,subprocess,tarfile,tempfile,zipfile
from pathlib import Path,PurePosixPath
ROOT=Path(__file__).resolve().parents[1]
BASE='319067c07b1bac0b0bec84a6ca1fb66f468f6a25'
p=argparse.ArgumentParser();p.add_argument('mode',choices=['android-before','android-after','desktop']);a=p.parse_args()
def run(args,**kwargs):return subprocess.run(args,check=True,**kwargs)
def snapshot(destination):
 if subprocess.run(['git','cat-file','-e',BASE],cwd=ROOT,capture_output=True).returncode:run(['git','fetch','--depth=1','origin',BASE],cwd=ROOT)
 raw=subprocess.check_output(['git','archive',BASE],cwd=ROOT)
 with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
  for entry in archive:
   if entry.isdir():continue
   name=PurePosixPath(entry.name)
   if entry.issym() or entry.islnk() or name.is_absolute() or '..' in name.parts:raise ValueError('Unsafe baseline member')
   if not entry.isfile():raise ValueError('Unsupported baseline member')
   target=destination.joinpath(*name.parts);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(entry).read())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
report=ROOT/('app/build/outputs/apk/ui-size.json' if a.mode.startswith('android') else 'desktop/build/ui-size.json')
report.parent.mkdir(parents=True,exist_ok=True)
head=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
if a.mode=='android-before':
 with tempfile.TemporaryDirectory(prefix='nearbyim-size-') as temp:
  tree=Path(temp);snapshot(tree)
  command=['bash','gradlew','--no-daemon','--max-workers=2','-PtestBuildType=preview',':app:assemblePreview']
  run(command,cwd=tree)
  apk=tree/'app/build/outputs/apk/preview/app-preview.apk'
  result={'baseline':BASE,'candidate':head,'kind':'actual optimized preview APK; same runner, toolchain, signing identity and build flags','before_bytes':apk.stat().st_size,'before_sha256':sha(apk)}
  report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
elif a.mode=='android-after':
 result=json.loads(report.read_text(encoding='utf-8'));apk=ROOT/'app/build/outputs/apk/preview/app-preview.apk';result.update(after_bytes=apk.stat().st_size,after_sha256=sha(apk));result['delta_bytes']=result['after_bytes']-result['before_bytes'];report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(report.read_text(encoding='utf-8'))
else:
 with tempfile.TemporaryDirectory(prefix='nearbyim-size-') as temp:
  tree=Path(temp);snapshot(tree)
  source=[]
  for directory in ['desktop/src/main/java','app/src/main/java/dev/ghost/nearbyim/core','app/src/main/java/dev/ghost/nearbyim/noise','app/src/main/java/dev/ghost/nearbyim/verification','third_party/noise-java/src','app/src/main/java/dev/ghost/nearbyim/i18n']:
   source += [p for p in (tree/directory).rglob('*.java') if p.name!='I18nResources.java']
  source.append(tree/'app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java')
  classes=tree/'classes';classes.mkdir();args=tree/'sources.txt';args.write_text('\n'.join('"'+str(path).replace('\\','/')+'"' for path in source),encoding='utf-8')
  run(['javac','--release','17','-encoding','UTF-8','-cp',str(ROOT/'desktop/build/lib/*'),'-d',str(classes),'@'+str(args)])
  candidate=ROOT/'desktop/build/lib/nearbyim-desktop.jar';baseline=tree/'baseline.jar'
  resources=tree/'desktop/src/main/resources'
  with zipfile.ZipFile(candidate) as current,zipfile.ZipFile(baseline,'w',zipfile.ZIP_DEFLATED) as old:
   for name in current.namelist():
    if name.endswith('.class') or 'emoji_catalog' in name or 'emoji-license' in name:continue
    original=resources/name
    data=original.read_bytes() if original.is_file() else current.read(name)
    old.writestr(name,data)
   for file in sorted(classes.rglob('*.class')):old.write(file,file.relative_to(classes).as_posix())
  package=ROOT/'desktop/build/package/NearbyIM';jar_path=('app' if platform.system()=='Windows' else 'lib/app')+'/nearbyim-desktop.jar'
  sizes=[]
  for label,jar in [('before',baseline),('after',candidate)]:
   output=tree/(label+('.zip' if platform.system()=='Windows' else '.tar.gz'))
   if platform.system()=='Windows':
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
     for file in sorted(package.rglob('*')):
      if file.is_file():
       relative=file.relative_to(package).as_posix();archive.write(jar if relative==jar_path else file,'NearbyIM/'+relative)
   else:
    with output.open('wb') as stream,gzip.GzipFile(fileobj=stream,mode='wb',mtime=0) as gz,tarfile.open(fileobj=gz,mode='w|') as archive:
     for file in sorted(package.rglob('*')):
      relative=file.relative_to(package).as_posix();source=jar if relative==jar_path else file;info=archive.gettarinfo(str(source),'NearbyIM/'+relative);info.mtime=0
      if info.isfile():
       with source.open('rb') as data:archive.addfile(info,data)
      else:archive.addfile(info)
   sizes.append({'kind':label,'bytes':output.stat().st_size,'sha256':sha(output)})
  result={'baseline':BASE,'candidate':head,'platform':platform.system(),'kind':'controlled portable archive comparison; identical candidate runtime/native libraries/fonts/build metadata, baseline Java classes and message resources compiled from exact main; size-only archives, not validated runtime candidates','archives':sizes,'delta_bytes':sizes[1]['bytes']-sizes[0]['bytes'],'catalog_gzip_bytes':(ROOT/'i18n/emoji/catalog.tsv.gz').stat().st_size,'new_runtime_dependencies':0}
  report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(report.read_text(encoding='utf-8'))
