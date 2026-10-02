#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
python3 ../tools/generate-i18n.py --check
mkdir -p build/lib build/classes build/tests
python3 - <<'PY'
from pathlib import Path
import hashlib, urllib.request
for line in Path('dependencies.txt').read_text().splitlines():
    expected, artifact = line.split()
    file = Path('build/lib') / artifact.split('/')[-1]
    if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest() != expected:
        data = urllib.request.urlopen('https://maven-central.storage-download.googleapis.com/maven2/' + artifact, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit('Dependency checksum mismatch: ' + artifact)
        file.write_bytes(data)
for line in Path('font-dependencies.txt').read_text().splitlines():
    expected, url = line.split()
    file=Path('build/fonts') / url.split('/')[-1]
    file.parent.mkdir(parents=True,exist_ok=True)
    if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest()!=expected:
        data=urllib.request.urlopen(url,timeout=60).read()
        if hashlib.sha256(data).hexdigest()!=expected:
            raise SystemExit('Font checksum mismatch: '+url)
        file.write_bytes(data)
PY
find src/main/java ../app/src/main/java/dev/ghost/nearbyim/core -name '*.java' > build/sources.txt
find ../app/src/main/java/dev/ghost/nearbyim/i18n -name '*.java' ! -name 'I18nResources.java' >> build/sources.txt
printf '%s\n' '../app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java' >> build/sources.txt
javac --release 17 -encoding UTF-8 -cp 'build/lib/*' -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
mkdir -p build/classes/dev/ghost/wozai/fonts
cp build/fonts/*.otf build/classes/dev/ghost/wozai/fonts/
cp ../app/src/main/res/drawable-nodpi/ic_launcher_artwork.png build/classes/dev/ghost/wozai/app-icon.png
rm -f build/lib/wozai-desktop.jar
jar --create --file build/lib/nearbyim-desktop.jar --main-class dev.ghost.wozai.Main -C build/classes .
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 17 -encoding UTF-8 -cp 'build/classes:build/lib/*' -d build/tests @build/test-sources.txt
for test in DesktopTests DataLocationTests BluetoothTests TransportTests StringsTests; do
    java -cp 'build/classes:build/tests:build/lib/*' dev.ghost.wozai.$test
done
if [ "${1:-}" = '--run' ]; then java -cp 'build/lib/*' dev.ghost.wozai.Main; fi
