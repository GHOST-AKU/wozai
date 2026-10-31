import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parents[2] / "tools/android-capture.py"


class CaptureTests(unittest.TestCase):
    def load_module(self):
        self.assertTrue(SOURCE.exists(), "The structured capture implementation is missing")
        spec = importlib.util.spec_from_file_location("android_capture", SOURCE)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def load_parser(self):
        return self.load_module().parse_event

    def event(self,event_name='operation.error',**fields):
        return dict(schema=1,session='c369c7e9-3c86-4eae-b450-8f706c70050c',
                    event=event_name,elapsed_ms=7,**fields)

    def line(self,event):
        return 'INSTRUMENTATION_STATUS: mesh_event='+json.dumps(event)

    def test_rejects_boolean_schema_and_raw_or_unknown_payload_fields(self):
        parse_event=self.load_parser()
        valid=self.event(operation='wifi.group_info',exception='SecurityException')
        for fields in [{'schema':True},{'mac':'01:02:03:04:05:06'},
                       {'ssid':'private-network'},{'diagnostic':'private detail'},
                       {'operation':{'mac':'01:02:03:04:05:06'}},{'elapsed_ms':[]}]:
            with self.subTest(fields=fields):
                self.assertIsNone(parse_event(self.line(dict(valid,**fields))))

    def test_accepts_only_bounded_per_run_tokens(self):
        parse_event=self.load_parser()
        for value in ['07c05c85b9624b9b','absent','redacted']:
            event=self.event('wifi.peer',token=value,status=3)
            self.assertEqual(parse_event(self.line(event)),event)
        for value in ['01:02:03:04:05:06','10.0.0.1','private-network','A'*16,
                      'a'*17,{'raw':'private'},['07c05c85b9624b9b']]:
            with self.subTest(value=value):
                self.assertIsNone(parse_event(self.line(self.event('wifi.peer',token=value,status=3))))

    def test_rejects_unknown_events_and_oversized_structured_lines(self):
        parse_event=self.load_parser()
        for event in [self.event('future.event'),
                      self.event(operation='x'*9000,exception='SecurityException')]:
            self.assertIsNone(parse_event(self.line(event)))

    def test_capture_fails_closed_on_oversized_raw_output(self):
        valid=self.line(self.event(operation='wifi.group_info',exception='SecurityException'))
        for output in ['x'*9000+'\n','diagnostic\n'*120000]:
            with self.subTest(output_bytes=len(output)), tempfile.TemporaryDirectory() as temp:
                fake=Path(temp)/'fake-adb'
                fake.write_text('#!'+sys.executable+'\nimport sys\nprint('+repr(valid)+',flush=True)\nsys.stdout.write('+repr(output)+')\n')
                fake.chmod(0o700)
                result=subprocess.run([sys.executable,str(SOURCE),'--adb',str(fake),'--seconds','2'],
                                      capture_output=True,text=True,timeout=5)
                self.assertNotEqual(result.returncode,0,'oversized capture must not be accepted')
                self.assertNotIn('x'*100,result.stdout+result.stderr)

    def test_accepts_current_probe_event_variants(self):
        parse_event=self.load_parser()
        fixtures=[
            self.event('run.start',mode='inventory',seconds=5,sdk=36,target_sdk=36,
                       manufacturer='Example',model='Model 1',data_path_tested=False,background_tested=False),
            self.event('run.end',data_path_tested=False,mesh_validation='pending_hardware_data_path'),
            self.event('wifi.group',present=False),
            self.event('wifi.group',present=True,owner=True,interface='p2p-wlan0-0',clients=1,ssid_token='07c05c85b9624b9b'),
            self.event('wifi.connection',group_formed=False,owner=False,owner_address_token=None),
            self.event('wifi.discover.request',accepted=True,data_path_proven=False),
            self.event('wifi.discover.request',accepted=False,reason_code=2),
            self.event('interface',name='wlan0',up=True,ipv4_count=1,ipv6_count=2,link_local_ipv6_count=1),
            self.event('network',token='07c05c85b9624b9b',interface=None,wifi=True,aware=False,
                       cellular=False,internet_capability=False,validated=False),
            self.event('ble.probe_peer',peer_token='07c05c85b9624b9b',rssi=-75),
            self.event('aware.service_discovered',peer_token='07c05c85b9624b9b'),
            self.event('wifi.broadcast',action='android.net.wifi.p2p.STATE_CHANGED'),
        ]
        for event in fixtures:
            with self.subTest(event=event): self.assertEqual(parse_event(self.line(event)),event)

    def test_capture_bounds_timeout_and_reaps_a_local_fake_process(self):
        module=self.load_module()
        with tempfile.TemporaryDirectory() as temp:
            fake=Path(temp)/'fake-adb.py';pid_file=Path(temp)/'pid'
            fake.write_text('import os,time\nfrom pathlib import Path\nPath('+repr(str(pid_file))+').write_text(str(os.getpid()))\ntime.sleep(30)\n')
            began=time.monotonic()
            with self.assertRaisesRegex(module.CaptureError,'timed out'):
                module.capture_events([sys.executable,str(fake)],timeout=.2)
            self.assertLess(time.monotonic()-began,2)
            self.assertTrue(pid_file.exists())
            with self.assertRaises(ProcessLookupError):
                import os
                os.kill(int(pid_file.read_text()),0)

    def test_capture_preserves_only_valid_events_and_exit_status(self):
        module=self.load_module()
        event=self.event(operation='wifi.group_info',exception='SecurityException')
        with tempfile.TemporaryDirectory() as temp:
            fake=Path(temp)/'fake-adb.py'
            fake.write_text('import sys\nprint("raw MAC=01:02:03:04:05:06")\nprint('+repr(self.line(event))+')\nsys.exit(3)\n')
            code,events=module.capture_events([sys.executable,str(fake)],timeout=2)
            self.assertEqual(code,3);self.assertEqual(events,[event])

    def test_preserves_permission_failure_without_raw_device_diagnostics(self):
        parse_event = self.load_parser()
        event = {"schema": 1, "session": "c369c7e9-3c86-4eae-b450-8f706c70050c",
                 "event": "operation.error", "elapsed_ms": 7,
                 "operation": "wifi.group_info", "exception": "SecurityException"}
        lines = ["private adb diagnostic: MAC=01:02:03:04:05:06",
                 "INSTRUMENTATION_STATUS: mesh_event=" + json.dumps(event),
                 "INSTRUMENTATION_RESULT: stream=private output"]
        self.assertEqual([e for line in lines if (e := parse_event(line)) is not None], [event])

    def test_rejects_invalid_or_unversioned_events(self):
        parse_event = self.load_parser()
        for line in ["INSTRUMENTATION_STATUS: mesh_event={broken",
                     "INSTRUMENTATION_STATUS: mesh_event=[]",
                     'INSTRUMENTATION_STATUS: mesh_event={"event":"run.end"}']:
            self.assertIsNone(parse_event(line))


if __name__ == "__main__":
    unittest.main()
