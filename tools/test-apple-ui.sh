#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
destination="$1"
label="$2"
mkdir -p build/apple-ui
if [[ "$destination" == *"platform=iOS Simulator"* ]]; then
  simulator_id="${destination##*id=}"; simulator_id="${simulator_id%%,*}"
  python3 - "$simulator_id" > "build/apple-ui/$label-boot.log" 2>&1 <<'PY'
import subprocess, sys
device = sys.argv[1]
subprocess.run(['xcrun', 'simctl', 'boot', device], check=False, timeout=30)
subprocess.run(['xcrun', 'simctl', 'bootstatus', device, '-b'], check=True, timeout=240)
PY
fi
xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM -destination "$destination" \
  -configuration Debug -derivedDataPath "apple/UITestBuild-$label" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build-for-testing > "build/apple-ui/$label-build.log" 2>&1
port_file="build/apple-ui/$label.port"
rm -f "$port_file"
java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer ui-server "$port_file" > "build/apple-ui/$label-java.log" 2>&1 &
peer_pid=$!
cleanup() { kill "$peer_pid" 2>/dev/null || true; wait "$peer_pid" 2>/dev/null || true; }
trap cleanup EXIT
python3 - "$port_file" "$peer_pid" <<'PY'
import os, pathlib, sys, time
path = pathlib.Path(sys.argv[1])
for attempt in range(100):
    os.kill(int(sys.argv[2]), 0)
    if path.exists() and path.read_text().strip(): break
    time.sleep(0.1)
else: raise SystemExit('The independent Java UI peer did not start')
PY
xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM -destination "$destination" \
  -configuration Debug -derivedDataPath "apple/UITestBuild-$label" \
  -resultBundlePath "build/apple-ui/$label.xcresult" \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 180 -maximum-test-execution-time-allowance 180 \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test-without-building | tee "build/apple-ui/$label-xcodebuild.log"
wait "$peer_pid"
cat "build/apple-ui/$label-java.log"
