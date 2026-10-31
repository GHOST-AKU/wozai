#!/usr/bin/env python3
"""Collect only versioned, redacted events from the standalone Android probe."""
import argparse
import json
import subprocess
import sys

PREFIX = 'INSTRUMENTATION_STATUS: mesh_event='

def parse_event(line):
    if not line.startswith(PREFIX):
        return None
    try:
        event = json.loads(line[len(PREFIX):])
    except (json.JSONDecodeError, ValueError):
        return None
    if not isinstance(event, dict) or event.get('schema') != 1 or not isinstance(event.get('event'), str):
        return None
    return event

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb')
    parser.add_argument('--serial')
    parser.add_argument('--mode', choices=['inventory','wifi-discover','wifi-group','aware','ble'], default='inventory')
    parser.add_argument('--seconds', type=int, default=8)
    args = parser.parse_args()
    command = [args.adb]
    if args.serial:
        command += ['-s',args.serial]
    command += ['shell','am','instrument','-w','-r','-e','mode',args.mode,'-e','seconds',str(max(2,min(30,args.seconds))),
                'org.nearbymesh.probe/.CapabilityProbe']
    try:
        result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                text=True, timeout=max(2,min(30,args.seconds))+15)
    except subprocess.TimeoutExpired:
        print('Probe capture timed out; inspect and stop the test process locally.',file=sys.stderr)
        return 1
    count = 0
    for line in result.stdout.splitlines():
        event = parse_event(line)
        if event is not None:
            print(json.dumps(event, ensure_ascii=False),flush=True)
            count += 1
    code = result.returncode
    if not count:
        print('No structured probe events; check device, package installation and permissions locally.',file=sys.stderr)
        return 1
    return code

if __name__ == '__main__':
    raise SystemExit(main())
