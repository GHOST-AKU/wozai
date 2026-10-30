#!/usr/bin/env python3
"""Capture real candidate processes; phone screenshots always use portrait."""
import json
from pathlib import Path
import subprocess
import time


def run(*args):
    return subprocess.check_output(args, text=True).strip()


output = Path('build/candidate-screens')
output.mkdir(parents=True, exist_ok=True)
bundle = 'dev.ghost.wozai.apple'
mac = Path('build/Mac/Build/Products/Release/NearbyIM.app').resolve()
subprocess.run(['open', str(mac)], check=True)
try:
    time.sleep(4)
    run('osascript', '-e', 'tell application "NearbyIM" to activate')
    run('screencapture', '-x', str(output / 'macos.png'))
finally:
    subprocess.run(['osascript', '-e', 'tell application "NearbyIM" to quit'], check=False)

# Build the simulator product once and install the identical app into both device classes.
with (output / 'simulator-build.log').open('w') as log:
    subprocess.run(['xcodebuild', '-project', 'apple/NearbyIM.xcodeproj', '-scheme', 'NearbyIM',
                    '-configuration', 'Release', '-destination', 'generic/platform=iOS Simulator',
                    '-derivedDataPath', 'build/Simulator', 'CODE_SIGNING_ALLOWED=NO', 'build'],
                   stdout=log, stderr=subprocess.STDOUT, check=True)
app = 'build/Simulator/Build/Products/Release-iphonesimulator/NearbyIM.app'
devices = json.loads(run('xcrun', 'simctl', 'list', 'devices', 'available', '--json'))['devices']
available = [d for runtime, group in devices.items() if 'iOS' in runtime for d in group if d.get('isAvailable')]
for kind, prefix in [('iphone-portrait', 'iPhone'), ('ipad', 'iPad')]:
    device = next(d for d in available if d['name'].startswith(prefix))
    udid = device['udid']
    try:
        subprocess.run(['xcrun', 'simctl', 'boot', udid], check=True)
        run('xcrun', 'simctl', 'bootstatus', udid, '-b')
        run('xcrun', 'simctl', 'status_bar', udid, 'override', '--time', '9:41', '--batteryState', 'charged', '--batteryLevel', '100')
        run('xcrun', 'simctl', 'install', udid, app)
        run('xcrun', 'simctl', 'launch', udid, bundle)
        time.sleep(4)
        run('xcrun', 'simctl', 'io', udid, 'screenshot', str(output / f'{kind}.png'))
    finally:
        subprocess.run(['xcrun', 'simctl', 'shutdown', udid], check=False)
(output / 'SOURCE_COMMIT.txt').write_text(run('git', 'rev-parse', 'HEAD') + '\n')
