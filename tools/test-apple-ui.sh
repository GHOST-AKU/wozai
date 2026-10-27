#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
destination="$1"
label="$2"
mode="${3:---all}"
case "$mode" in --all|--prepare-only|--test-only) ;; *) echo 'Expected --all, --prepare-only or --test-only' >&2; exit 2 ;; esac
derived="apple/UITestBuild-$label"
if [[ "$destination" == *"platform=iOS Simulator"* ]]; then derived="apple/UITestBuild-ios"; fi
mkdir -p build/apple-ui
peer_pid=""
timeline_log_pid=""
simulator_id=""
prepared=false
cleanup() {
  if [[ -n "$peer_pid" ]]; then kill "$peer_pid" 2>/dev/null || true; wait "$peer_pid" 2>/dev/null || true; fi
  if [[ -n "$timeline_log_pid" ]]; then kill "$timeline_log_pid" 2>/dev/null || true; wait "$timeline_log_pid" 2>/dev/null || true; fi
  if [[ -f "build/apple-ui/$label-java.log" ]]; then cat "build/apple-ui/$label-java.log"; fi
  if [[ -f "build/apple-ui/$label-timeline.log" ]]; then cat "build/apple-ui/$label-timeline.log"; fi
  if [[ -n "$simulator_id" && ( "$mode" != --prepare-only || "$prepared" != true ) ]]; then xcrun simctl shutdown "$simulator_id" > /dev/null 2>&1 || true; fi
}
trap cleanup EXIT
if [[ "$destination" == "platform=macOS" ]]; then
  /usr/bin/log stream --style compact --level debug --predicate 'subsystem == "dev.ghost.wozai.apple" AND (category == "Timeline" OR category == "Columns")' > "build/apple-ui/$label-timeline.log" 2>&1 &
  timeline_log_pid=$!
fi
if [[ "$destination" == *"platform=iOS Simulator"* ]]; then
  simulator_id="${destination##*id=}"; simulator_id="${simulator_id%%,*}"
  if [[ "$mode" != --test-only ]]; then
  python3 - "$simulator_id" > "build/apple-ui/$label-boot.log" 2>&1 <<'PY'
import subprocess, sys
device = sys.argv[1]
subprocess.run(['xcrun', 'simctl', 'boot', device], check=False, timeout=30)
subprocess.run(['xcrun', 'simctl', 'bootstatus', device, '-b'], check=True, timeout=360)
PY
  fi
fi
if [[ "$mode" != --test-only ]]; then
xcodebuild -project apple/NearbyIM.xcodeproj -scheme NearbyIM -destination "$destination" \
  -configuration Debug -derivedDataPath "$derived" \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- build-for-testing > "build/apple-ui/$label-build.log" 2>&1
fi
if [[ "$mode" == --prepare-only ]]; then prepared=true; exit 0; fi
port_file="build/apple-ui/$label.port"
rm -f "$port_file"
java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer ui-server "$port_file" > "build/apple-ui/$label-java.log" 2>&1 &
peer_pid=$!
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
  -configuration Debug -derivedDataPath "$derived" \
  -resultBundlePath "build/apple-ui/$label.xcresult" \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 360 -maximum-test-execution-time-allowance 360 \
  CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=- test-without-building | tee "build/apple-ui/$label-xcodebuild.log"
wait "$peer_pid"
