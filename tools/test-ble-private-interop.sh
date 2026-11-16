#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/ble-private
dotnet build windows-next/Tests/BleInterop/BleInterop.csproj -c Release
binary="$(swift build --package-path apple --show-bin-path)/WozaiInterop"
python3 - "$binary" <<'PY'
import pathlib, subprocess, sys, time
root = pathlib.Path('build/ble-private')
swift_port, relay_port = root/'swift.port', root/'relay.port'
for path in (swift_port, relay_port):
    path.unlink(missing_ok=True)
processes, logs = [], []
def launch(command, name):
    log = (root/name).open('w'); logs.append(log)
    process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT); processes.append(process)
    return process
def port(path, process):
    for _ in range(200):
        if process.poll() is not None: raise RuntimeError('Peer exited: '+str(path))
        if path.exists() and path.read_text().strip(): return path.read_text().strip()
        time.sleep(.05)
    raise TimeoutError(str(path))
try:
    swift = launch([sys.argv[1], 'server', str(swift_port), '--ble-values'], 'swift.log')
    relay = launch(['dotnet', 'windows-next/Tests/BleInterop/bin/Release/net8.0/BleInterop.dll', port(swift_port, swift), str(relay_port)], 'dotnet.log')
    java = launch(['java', '-cp', 'build/apple-interop/java', 'dev.ghost.nearbyim.core.AppleInteropPeer', 'client', port(relay_port, relay)], 'java.log')
    for process in (java, swift, relay):
        assert process.wait(timeout=40) == 0, 'Independent peer failed; inspect logs'
    print('PASS: Java NIM4 ↔ production C# BLE carrier ↔ production Swift BLE carrier ↔ ChatSession (ATT delivery simulated)')
finally:
    for process in processes:
        if process.poll() is None: process.kill()
        process.wait()
    for log in logs: log.close()
    for path in root.glob('*.log'): print(path.name+'\n'+path.read_text())
PY
