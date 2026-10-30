#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/apple-interop/java
find third_party/noise-java/src app/src/main/java/dev/ghost/nearbyim/noise -name '*.java' > build/apple-interop/crypto-sources.txt
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d build/apple-interop/java \
  @build/apple-interop/crypto-sources.txt app/src/main/java/dev/ghost/nearbyim/core/*.java \
  app/src/main/java/dev/ghost/nearbyim/i18n/UiText.java \
  app/src/main/java/dev/ghost/nearbyim/i18n/LocalizedIllegalArgumentException.java tests/apple/AppleInteropPeer.java tests/apple/AppleAttachmentPeer.java
if [[ ${1:-} == --compile-java ]]; then exit 0; fi
swift build --package-path apple --product WozaiInterop
apple_binary="$(swift build --package-path apple --show-bin-path)/WozaiInterop"
peer_pid=""
cleanup() { if [[ -n "$peer_pid" ]]; then kill "$peer_pid" 2>/dev/null || true; wait "$peer_pid" 2>/dev/null || true; fi; }
trap cleanup EXIT
await_port() {
  python3 - "$1" "$peer_pid" <<'PY'
import os, pathlib, sys, time
path = pathlib.Path(sys.argv[1]); pid = int(sys.argv[2])
for attempt in range(200):
    os.kill(pid, 0)
    if path.exists() and path.read_text().strip():
        print(path.read_text().strip()); break
    time.sleep(0.1)
else: raise SystemExit('Peer did not become ready within 20 seconds')
PY
}
rm -f build/apple-interop/java.port build/apple-interop/swift.port
java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer server build/apple-interop/java.port > build/apple-interop/java-server.log 2>&1 &
peer_pid=$!
java_port="$(await_port build/apple-interop/java.port)"
"$apple_binary" client "$java_port" | tee build/apple-interop/swift-client.log
wait "$peer_pid"; peer_pid=""
cat build/apple-interop/java-server.log
"$apple_binary" server build/apple-interop/swift.port > build/apple-interop/swift-server.log 2>&1 &
peer_pid=$!
swift_port="$(await_port build/apple-interop/swift.port)"
java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer client "$swift_port" | tee build/apple-interop/java-client.log
wait "$peer_pid"; peer_pid=""
cat build/apple-interop/swift-server.log

# Both real Noise roles also exchange multi-block files through the shipping Java engine.
for role in client server; do
  case_root="$(mktemp -d "${TMPDIR:-/tmp}/wozai-apple-files.XXXXXX")"
  port_file="$case_root/peer.port"
  if [[ $role == client ]]; then
    java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleAttachmentPeer server "$port_file" "$case_root/java" > "build/apple-interop/java-files-$role.log" 2>&1 &
    peer_pid=$!
    port="$(await_port "$port_file")"
    "$apple_binary" client "$port" --files | tee "build/apple-interop/swift-files-$role.log"
  else
    "$apple_binary" server "$port_file" --files > "build/apple-interop/swift-files-$role.log" 2>&1 &
    peer_pid=$!
    port="$(await_port "$port_file")"
    java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleAttachmentPeer client "$port" "$case_root/java" | tee "build/apple-interop/java-files-$role.log"
  fi
  wait "$peer_pid"; peer_pid=""
  cat "build/apple-interop/swift-files-$role.log" "build/apple-interop/java-files-$role.log"
  rm -r "$case_root"
done

# Negotiate exact-ID read receipts in both authenticated roles.
bash tools/test-read-receipt-interop.sh --compiled
