#!/usr/bin/env python3
"""Package only an input-bound, 16KiB-aligned Android arm64 Mesh executable."""
import argparse,hashlib,json,os,shutil,struct,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def sources(root):
    files=[root/'mesh/Cargo.toml',root/'mesh/Cargo.lock',*(root/'mesh/src').glob('*.rs')]
    return {str(p.relative_to(root)):sha(p) for p in sorted(files)}
def check_elf(data):
    if len(data)<64 or data[:6]!=b'\x7fELF\x02\x01' or struct.unpack_from('<H',data,18)[0]!=183:raise ValueError('Android arm64 ELF required')
    phoff=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
    if size!=56 or count>128 or phoff+size*count>len(data):raise ValueError('ELF program headers invalid')
    interpreter=False;loads=0
    for n in range(count):
        kind,flags,offset,vaddr,paddr,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,phoff+n*size)
        if offset+filesz>len(data):raise ValueError('ELF file extent invalid')
        if kind==1:
            loads+=1
            if align<16384:raise ValueError('16KiB LOAD alignment required')
        if kind==3:interpreter=data[offset:offset+filesz]==b'/system/bin/linker64\0'
    if not interpreter or not loads:raise ValueError('Android linker64 interpreter required')
def check_binding(root,binary,metadata):
    if metadata['sha256']!=sha(binary):raise ValueError('Mesh executable hash mismatch')
    for name,digest in metadata['sources'].items():
        if sha(root/name)!=digest:raise ValueError('Mesh executable source changed: '+name)
    # Source list completeness is checked by the real packaging command.
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--record-build',action='store_true');parser.add_argument('--output',type=Path);args=parser.parse_args()
    binary=ROOT/'mesh/target/aarch64-linux-android/release/mesh-lab';check_elf(binary.read_bytes())
    record=ROOT/'mesh/target/android-core-build.json'
    if args.record_build:
        metadata={'sha256':sha(binary),'sources':sources(ROOT),'source_commit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),'binding':'caller-built-before-recording'}
        record.write_text(json.dumps(metadata,indent=2)+'\n')
    else:
        metadata=json.loads(record.read_text()) if record.exists() else json.loads((ROOT/'mesh/evidence/native-radio-android-core.json').read_text())
        check_binding(ROOT,binary,metadata)
        if metadata['sources']!=sources(ROOT):raise ValueError('Mesh executable source list mismatch')
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=args.output.parent,delete=False) as temp:
            temporary=Path(temp.name)
            try:
                with binary.open('rb') as source:shutil.copyfileobj(source,temp)
                temp.flush();os.fsync(temp.fileno())
            except BaseException:temporary.unlink(missing_ok=True);raise
        temporary.chmod(0o755);os.replace(temporary,args.output)
        (args.output.parent/'mesh-core-build.json').write_text(json.dumps(metadata,indent=2)+'\n')
    print(json.dumps({'bytes':binary.stat().st_size,'sha256':metadata['sha256'],'output':str(args.output) if args.output else None}))
if __name__=='__main__':main()
