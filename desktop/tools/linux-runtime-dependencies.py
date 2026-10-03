#!/usr/bin/env python3
"""Derive package dependencies from both native executables, not compiler guesses."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

image = Path(sys.argv[1]).resolve()
binaries = [image / 'bin/NearbyIM', image / 'lib/app/libwozai_bluetooth.so']
with tempfile.TemporaryDirectory(prefix='nearbyim-shlibdeps-') as temporary:
    metadata = Path(temporary) / 'debian'
    metadata.mkdir()
    (metadata / 'control').write_text('Source: nearbyim\n\nPackage: nearbyim\nArchitecture: any\nDescription: NearbyIM desktop client\n')
    result = subprocess.run(['dpkg-shlibdeps', '-O', *['-e' + str(binary) for binary in binaries]],
                            cwd=temporary, text=True, capture_output=True, check=True)
    if result.stderr:
        print(result.stderr, file=sys.stderr, end='')
    depends = next(line.removeprefix('shlibs:Depends=') for line in result.stdout.splitlines()
                   if line.startswith('shlibs:Depends='))

requirements = ['Native package dependencies: ' + depends,
                'Also requires BlueZ and X11/font/audio libraries listed in README.md.', '']
# Include bundled JVM libraries: tarball users need the highest symbol version
# used anywhere in the image, regardless of which compiler built the launcher.
maximum = {}
for binary in sorted(image.rglob('*')):
    if not binary.is_file():
        continue
    with binary.open('rb') as stream:
        if stream.read(4) != b'\x7fELF':
            continue
    info = subprocess.check_output(['readelf', '--version-info', str(binary)], text=True)
    versions = re.findall(r'Name: ((?:GLIBC|GLIBCXX|CXXABI)_[\d.]+)', info)
    for family in ('GLIBC', 'GLIBCXX', 'CXXABI'):
        matches = [v for v in versions if v.startswith(family + '_')]
        if not matches:
            continue
        latest = max(matches, key=lambda v: tuple(map(int, v.split('_')[1].split('.'))))
        if family not in maximum or tuple(map(int, latest.split('_')[1].split('.'))) > tuple(map(int, maximum[family].split('_')[1].split('.'))):
            maximum[family] = latest
requirements.append('Minimum symbol versions across launcher, Bluetooth backend and bundled JVM:')
requirements.extend(maximum.values())
(image / 'runtime-requirements.txt').write_text('\n'.join(requirements) + '\n')
print(depends)
