#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
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
PY
find src/main/java ../app/src/main/java/dev/ghost/nearbyim/core -name '*.java' > build/sources.txt
printf '%s\n' '../app/src/main/java/dev/ghost/nearbyim/storage/TrustPolicy.java' >> build/sources.txt
javac --release 17 -encoding UTF-8 -cp 'build/lib/*' -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
cp ../app/src/main/res/drawable-nodpi/ic_launcher_artwork.png build/classes/dev/ghost/wozai/app-icon.png
jar --create --file build/lib/wozai-desktop.jar --main-class dev.ghost.wozai.Main -C build/classes .
find src/test/java -name '*.java' > build/test-sources.txt
javac --release 17 -encoding UTF-8 -cp 'build/classes:build/lib/*' -d build/tests @build/test-sources.txt
java -cp 'build/classes:build/tests:build/lib/*' dev.ghost.wozai.DesktopTests
if [ "${1:-}" = '--run' ]; then java -cp 'build/lib/*' dev.ghost.wozai.Main; fi
