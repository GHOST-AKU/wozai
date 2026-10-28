#!/usr/bin/env python3
"""Derive package dependencies from every ELF, including the bundled JVM."""
from pathlib import Path
import re
import subprocess
import sys
import tempfile

image = Path(sys.argv[1]).resolve()
binaries = []
for binary in sorted(image.rglob('*')):
    if binary.is_file():
        with binary.open('rb') as stream:
            if stream.read(4) == b'\x7fELF':
                binaries.append(binary)

# Ubuntu 22.04's supported system ABI. Check requirements, not version
# definitions exported by a bundled library, before generating any package.
limits = {'GLIBC': (2, 35), 'GLIBCXX': (3, 4, 30), 'CXXABI': (1, 3, 13)}
maximum = {}
for binary in binaries:
    info = subprocess.check_output(['readelf', '--wide', '--version-info', str(binary)], text=True)
    needs = info.partition('Version needs section')[2]
    versions = re.findall(r'Name: ((?:GLIBC|GLIBCXX|CXXABI)_[A-Za-z0-9_.]+)', needs)
    for version in versions:
        family, number = version.split('_', 1)
        if not re.fullmatch(r'\d+(?:\.\d+)*', number) or tuple(map(int, number.split('.'))) > limits[family]:
            raise SystemExit(f'Ubuntu 22.04 ABI limit exceeded: {binary.relative_to(image)} requires {version}')
        if family not in maximum or tuple(map(int, number.split('.'))) > tuple(map(int, maximum[family].split('_', 1)[1].split('.'))):
            maximum[family] = version

# Resolve the JVM's private libjli/libjava/libjvm libraries inside the image.
# dpkg-shlibdeps still verifies metadata for external system libraries; never
# use --ignore-missing-info to conceal an unsatisfied runtime dependency.
library_directories = sorted({binary.parent for binary in binaries})
with tempfile.TemporaryDirectory(prefix='nearbyim-shlibdeps-') as temporary:
    metadata = Path(temporary) / 'debian'
    metadata.mkdir()
    (metadata / 'control').write_text('Source: nearbyim\n\nPackage: nearbyim\nArchitecture: any\nDescription: NearbyIM desktop client\n')
    result = subprocess.run(['dpkg-shlibdeps', '-O', *['-l' + str(path) for path in library_directories],
                             *['-e' + str(binary) for binary in binaries]],
                            cwd=temporary, text=True, capture_output=True)
    if result.stderr:
        print(result.stderr, file=sys.stderr, end='')
    result.check_returncode()
    depends = next(line.removeprefix('shlibs:Depends=') for line in result.stdout.splitlines()
                   if line.startswith('shlibs:Depends='))

requirements = ['Native and bundled JVM package dependencies: ' + depends,
                'Also requires BlueZ and X11/font/audio libraries listed in README.md.',
                'ABI baseline: Ubuntu 22.04 (GLIBC <= 2.35, GLIBCXX <= 3.4.30, CXXABI <= 1.3.13).', '']
# Include bundled JVM libraries: tarball users need the highest symbol version
# used anywhere in the image, regardless of which compiler built the launcher.
requirements.append('Minimum symbol versions across launcher, Bluetooth backend and bundled JVM:')
requirements.extend(maximum.values())
(image / 'runtime-requirements.txt').write_text('\n'.join(requirements) + '\n')
print(depends)
