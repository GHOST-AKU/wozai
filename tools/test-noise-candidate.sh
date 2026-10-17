#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
python3 - <<'PY'
from pathlib import Path
import json,hashlib
root=Path('tests/noise-candidate')
vendor=Path('third_party/noise-java/src')
expected=json.loads((root/'LOCAL_SHA256.json').read_text())
actual={p.relative_to(vendor).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in vendor.rglob('*.java')}
if actual!=expected: raise SystemExit('Noise candidate source pin mismatch')
print('Noise candidate: 29 pinned upstream sources; explicit 3-file adapter/RFC 7748/DH validation patch')
PY
mkdir -p build/noise-candidate
find tests/noise-candidate third_party/noise-java/src app/src/main/java/dev/ghost/nearbyim/noise -name '*.java' > build/noise-candidate/sources.txt
printf '%s\n' app/src/main/java/dev/ghost/nearbyim/core/TransferDiagnostics.java app/src/main/java/dev/ghost/nearbyim/core/DeviceIdentity.java app/src/main/java/dev/ghost/nearbyim/core/Frame.java app/src/main/java/dev/ghost/nearbyim/core/StreamConnection.java app/src/main/java/dev/ghost/nearbyim/core/UnsupportedProtocolException.java >> build/noise-candidate/sources.txt
javac --release 8 -encoding UTF-8 -d build/noise-candidate @build/noise-candidate/sources.txt
java -Xmx32m -cp build/noise-candidate dev.ghost.nearbyim.noise.NoiseLibraryChecks
java -Xmx32m -cp build/noise-candidate dev.ghost.nearbyim.noise.NoiseChannelChecks
