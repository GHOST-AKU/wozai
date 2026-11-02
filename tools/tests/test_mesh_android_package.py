import importlib.util
from pathlib import Path
import struct,tempfile,unittest
ROOT=Path(__file__).resolve().parents[2]
class MeshPackageTests(unittest.TestCase):
    def module(self):
        spec=importlib.util.spec_from_file_location('mesh_package',ROOT/'tools/package-android-mesh.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
    def test_linux_or_wrong_machine_or_alignment_rejected(self):
        m=self.module()
        def elf(machine=183,align=16384,interp=b'/system/bin/linker64\0'):
            data=bytearray(512);data[:6]=b'\x7fELF\x02\x01';struct.pack_into('<HHIQQQIHHHHHH',data,16,3,machine,1,0,64,0,0,64,56,2,0,0,0)
            struct.pack_into('<IIQQQQQQ',data,64,1,5,0,0,0,512,512,align)
            struct.pack_into('<IIQQQQQQ',data,120,3,4,256,0,0,len(interp),len(interp),1);data[256:256+len(interp)]=interp;return bytes(data)
        m.check_elf(elf())
        for bad in [b'bad',elf(machine=62),elf(align=4096),elf(interp=b'/lib/ld-linux-aarch64.so.1\0')]:
            with self.assertRaises(ValueError):m.check_elf(bad)
    def test_metadata_binds_sources_and_binary(self):
        m=self.module()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'mesh/src').mkdir(parents=True)
            (root/'mesh/src/main.rs').write_text('source');binary=root/'core';binary.write_bytes(b'core')
            metadata={'sha256':m.sha(binary),'sources':{'mesh/src/main.rs':m.sha(root/'mesh/src/main.rs')}}
            m.check_binding(root,binary,metadata)
            (root/'mesh/src/main.rs').write_text('changed')
            with self.assertRaises(ValueError):m.check_binding(root,binary,metadata)
