#!/usr/bin/env python3
"""Collect only versioned, redacted events from the standalone Android probe."""
import argparse
import json
import os
import re
import selectors
import signal
import subprocess
import sys
import time

PREFIX = 'INSTRUMENTATION_STATUS: mesh_event='
MAX_LINE_BYTES = 8192
MAX_TOTAL_BYTES = 1024 * 1024
COMMON = {'schema','session','event','elapsed_ms'}
# Deliberately enumerate the current probe export, so a new field needs privacy review.
EVENT_FIELDS = {
    'run.start':'mode seconds sdk target_sdk manufacturer model data_path_tested background_tested',
    'run.invalid_mode':'', 'run.invalid_duration':'using_default', 'run.interrupted':'',
    'run.end':'data_path_tested mesh_validation', 'cleanup.timeout':'',
    'features':'wifi_direct wifi_aware bluetooth bluetooth_le', 'permission':'name granted',
    'location':'enabled', 'wifi.manager_absent':'', 'wifi.state':'enabled',
    'wifi.sta_ap':'reported_supported', 'wifi.sta_local_only':'reported_supported',
    'aware.state':'manager_present available', 'aware.resources':'data_paths publish_sessions subscribe_sessions',
    'bluetooth.adapter_absent':'', 'bluetooth.state':'enabled advertiser_present scanner_present multiple_advertisements',
    'interface':'name up ipv4_count ipv6_count link_local_ipv6_count',
    'network':'token interface wifi aware cellular internet_capability validated',
    'wifi.p2p_manager_absent':'', 'wifi.channel_disconnected':'', 'wifi.broadcast':'action',
    'wifi.group':'present owner interface clients ssid_token',
    'wifi.connection':'group_formed owner owner_address_token', 'wifi.peer_count':'count',
    'wifi.peer':'token status', 'wifi.create_group.skipped_stopping':'',
    'wifi.create_group.skipped_existing_group':'',
    'wifi.discover.request':'accepted data_path_proven reason_code',
    'wifi.create_group.request':'accepted data_path_proven reason_code',
    'wifi.discovery_stop':'accepted data_path_proven reason_code',
    'aware.unavailable':'', 'aware.attach_failed':'', 'aware.attached':'',
    'aware.publish_started':'', 'aware.publish_failed':'', 'aware.subscribe_started':'',
    'aware.subscribe_failed':'', 'aware.service_discovered':'peer_token',
    'ble.scan_failed':'reason_code', 'ble.probe_peer':'peer_token rssi',
    'ble.advertise_started':'connectable', 'ble.advertise_failed':'reason_code',
    'ble.unavailable':'', 'ble.gatt_service_added':'status', 'ble.gatt_connection':'status state',
    'ble.gatt_server_absent':'', 'ble.gatt_service_request':'accepted', 'ble.scanner_absent':'',
    'ble.scan_requested':'', 'ble.advertiser_absent':'',
    'wifi.group_cleanup_skipped_foreign_or_absent':'', 'wifi.group_removed':'',
    'wifi.group_remove_failed':'reason_code', 'operation.error':'operation exception',
}
EVENT_FIELDS = {name:set(fields.split()) for name,fields in EVENT_FIELDS.items()}
BOOL_FIELDS = set('data_path_tested background_tested wifi_direct wifi_aware bluetooth bluetooth_le granted enabled reported_supported manager_present available advertiser_present scanner_present multiple_advertisements up wifi aware cellular internet_capability validated present owner group_formed accepted data_path_proven connectable'.split())
INT_FIELDS = set('sdk target_sdk seconds using_default data_paths publish_sessions subscribe_sessions ipv4_count ipv6_count link_local_ipv6_count clients count status state reason_code rssi'.split())
OPERATIONS = set('inventory wifi.initialize wifi.discover wifi.create_group aware.attach ble.start wifi.flags aware.flags bluetooth.flags wifi.group_info wifi.connection_info wifi.peers aware.publish aware.subscribe ble.scan_result ble.gatt_server ble.scan ble.advertise cleanup.ble_scan cleanup.ble_advertise cleanup.gatt cleanup.aware_publish cleanup.aware_subscribe cleanup.aware_session interfaces.final cleanup.wifi_discovery cleanup.wifi_group cleanup.wifi_owned_group'.split())
PERMISSIONS = {'android.permission.'+name for name in ['ACCESS_FINE_LOCATION','NEARBY_WIFI_DEVICES','BLUETOOTH_SCAN','BLUETOOTH_ADVERTISE','BLUETOOTH_CONNECT']}
ACTIONS = {'android.net.wifi.p2p.'+name for name in ['STATE_CHANGED','PEERS_CHANGED','CONNECTION_STATE_CHANGE']}

def safe_field(name,value,event):
    if name=='schema': return type(value) is int and value==1
    if name=='session':
        return isinstance(value,str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',value) is not None
    if name=='event': return isinstance(value,str) and value in EVENT_FIELDS
    if name=='elapsed_ms': return type(value) is int and 0<=value<=86400000
    if name=='token' or name.endswith('_token'):
        if value is None: return name=='owner_address_token'
        return isinstance(value,str) and (value in {'absent','redacted'} or re.fullmatch(r'[0-9a-f]{16}',value) is not None)
    if name in BOOL_FIELDS: return type(value) is bool
    if name in INT_FIELDS:
        return type(value) is int and (-127<=value<=127 if name=='rssi' else 0<=value<=2147483647)
    if name=='operation': return isinstance(value,str) and value in OPERATIONS
    if name=='exception': return isinstance(value,str) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]{0,79}',value) is not None
    if name=='mode': return isinstance(value,str) and value in {'inventory','wifi-discover','wifi-group','aware','ble'}
    if name=='action': return isinstance(value,str) and value in ACTIONS
    if name=='mesh_validation': return value=='pending_hardware_data_path'
    if name=='name' and event=='permission': return isinstance(value,str) and value in PERMISSIONS
    if name in {'name','interface'}:
        return (name=='interface' and value is None) or (isinstance(value,str) and re.fullmatch(r'[A-Za-z0-9_.-]{1,64}',value) is not None)
    if name in {'manufacturer','model'}: return isinstance(value,str) and 0<len(value)<=128 and value.isprintable()
    return False

def parse_event(line):
    if not isinstance(line,str) or len(line)>MAX_LINE_BYTES or not line.startswith(PREFIX):
        return None
    try:
        if len(line.encode('utf-8'))>MAX_LINE_BYTES: return None
        event = json.loads(line[len(PREFIX):])
    except (ValueError,UnicodeError,RecursionError):
        return None
    if not isinstance(event,dict) or not COMMON<=event.keys(): return None
    name=event['event']
    if not isinstance(name,str) or name not in EVENT_FIELDS or not event.keys()<=COMMON|EVENT_FIELDS[name]:
        return None
    if not all(safe_field(key,value,name) for key,value in event.items()): return None
    return event

class CaptureError(Exception):
    pass

def capture_events(command,timeout,max_total=MAX_TOTAL_BYTES,max_line=MAX_LINE_BYTES):
    """Bound raw adb output before decoding; abort and reap the command on every limit."""
    process=subprocess.Popen(command,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                             start_new_session=os.name=='posix')
    deadline=time.monotonic()+timeout
    events=[];pending=bytearray();total=0;completed=False
    def consume(line):
        if len(line)>max_line: raise CaptureError('Probe capture line exceeded the byte limit.')
        try: decoded=line.decode('utf-8')
        except UnicodeError: return
        event=parse_event(decoded)
        if event is not None: events.append(event)
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout,selectors.EVENT_READ)
            while True:
                remaining=deadline-time.monotonic()
                if remaining<=0: raise CaptureError('Probe capture timed out; inspect and stop the test process locally.')
                if not selector.select(min(.1,remaining)): continue
                chunk=os.read(process.stdout.fileno(),min(4096,max_total-total+1))
                if not chunk:
                    if pending: consume(bytes(pending))
                    break
                total+=len(chunk)
                if total>max_total: raise CaptureError('Probe capture exceeded the total byte limit.')
                pending.extend(chunk)
                while b'\n' in pending:
                    line,_,rest=pending.partition(b'\n');pending=bytearray(rest)
                    consume(bytes(line))
                if len(pending)>max_line: raise CaptureError('Probe capture line exceeded the byte limit.')
        try: code=process.wait(timeout=max(.001,deadline-time.monotonic()))
        except subprocess.TimeoutExpired:
            raise CaptureError('Probe capture timed out; inspect and stop the test process locally.') from None
        completed=True
        return code,events
    finally:
        if not completed or process.poll() is None:
            if os.name=='posix':
                try: os.killpg(process.pid,signal.SIGKILL)
                except ProcessLookupError: pass
            else: process.kill()
        process.wait(timeout=2)
        process.stdout.close()

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
        code,events=capture_events(command,max(2,min(30,args.seconds))+15)
    except (CaptureError,OSError) as error:
        print(str(error) if isinstance(error,CaptureError) else 'Probe capture could not start; check adb locally.',file=sys.stderr)
        return 1
    for event in events: print(json.dumps(event,ensure_ascii=False),flush=True)
    if not events:
        print('No structured probe events; check device, package installation and permissions locally.',file=sys.stderr)
        return 1
    return code

if __name__ == '__main__':
    raise SystemExit(main())
