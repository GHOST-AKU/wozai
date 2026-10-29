#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ${1:-} != --compiled ]]; then bash tools/test-apple-interop.sh --compile-java; fi
swift build --package-path apple --product WozaiInterop
apple_binary="$(swift build --package-path apple --show-bin-path)/WozaiInterop"
peer_pid=""
cleanup() { if [[ -n "$peer_pid" ]]; then kill "$peer_pid" 2>/dev/null || true; wait "$peer_pid" 2>/dev/null || true; fi; }
trap cleanup EXIT
for role in client server; do
  port_file="build/apple-interop/read-$role.port"
  rm -f "$port_file"
  if [[ $role == client ]]; then
    java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer server "$port_file" --reads > "build/apple-interop/read-java-$role.log" 2>&1 &
  else
    "$apple_binary" server "$port_file" --reads > "build/apple-interop/read-swift-$role.log" 2>&1 &
  fi
  peer_pid=$!
  port="$(python3 - "$port_file" "$peer_pid" <<'PY'
import pathlib,sys,os,time
p=pathlib.Path(sys.argv[1]);pid=int(sys.argv[2])
for _ in range(200):
    os.kill(pid,0)
    if p.exists() and p.read_text().strip():
        print(p.read_text().strip());break
    time.sleep(.1)
else:raise SystemExit('Read peer did not become ready')
PY
)"
  if [[ $role == client ]]; then
    "$apple_binary" client "$port" --reads | tee "build/apple-interop/read-swift-$role.log"
  else
    java -cp build/apple-interop/java dev.ghost.nearbyim.core.AppleInteropPeer client "$port" --reads | tee "build/apple-interop/read-java-$role.log"
  fi
  wait "$peer_pid";peer_pid=""
  cat "build/apple-interop/read-java-$role.log" "build/apple-interop/read-swift-$role.log"
done
