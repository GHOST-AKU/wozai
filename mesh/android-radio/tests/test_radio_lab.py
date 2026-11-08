"""Host-only controller regressions; these tests do not establish radio acceptance."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shlex
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

CONTROLLER = Path(__file__).resolve().parents[2] / 'tools/radio-lab.py'
if CONTROLLER.exists():
    spec = importlib.util.spec_from_file_location('radio_lab', CONTROLLER)
    lab = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(lab)
else:
    lab = None


class ControllerTests(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(lab, 'USB radio controller is not implemented')
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)

    def args(self, *extra):
        with contextlib.redirect_stderr(io.StringIO()):
            return lab.parse_args(['--mode', 'ble', '--a', 'USB_A', '--b', 'USB_B', '--c', 'USB_C', *extra])

    def psk(self, value=b"lab '$(id); password"):
        path = Path(self.temp.name) / 'psk'
        path.write_bytes(value)
        return path

    def test_requires_one_mode_and_three_distinct_usb_serials(self):
        for extra in (['--mode', 'both'], ['--b', 'USB_A'], ['--a', '-bad'],
                      ['--a', 'x;id'], ['--a', '192.0.2.1:5555'], ['--c', 'emulator-5554'],
                      ['--a', 'x' * 129], ['--messages-per-side', '1'], ['--messages-per-side', '13']):
            with self.subTest(extra=extra), self.assertRaises(SystemExit):
                self.args(*extra)
        self.assertEqual(self.args().messages_per_side, 12)

    def test_core_path_is_absolute_bounded_and_cannot_inject_remote_shell(self):
        for path in ('relative', '/tmp/x;id', '/tmp/$(id)', '/tmp/../x', '/tmp//x', '/tmp/' + 'x' * 201):
            with self.subTest(path=path), self.assertRaises(SystemExit):
                self.args('--core-path', path)
        args = self.args('--core-path', '/data/local/tmp/custom-core')
        words = shlex.split(lab.core_argv(args, 'B')[-1])
        self.assertEqual(words[:3], ['exec', '/data/local/tmp/custom-core', 'run'])
        self.assertIn('--relay', words)
        self.assertNotIn('--relay', shlex.split(lab.core_argv(args, 'A')[-1]))
        self.assertEqual(words[words.index('--listen') + 1], '127.0.0.1:39770')
        self.assertEqual(words[words.index('--state') + 1], '/data/local/tmp/nearby-mesh-radio/ble/B')

    def test_aware_psk_is_required_and_shell_quoted_as_one_argument(self):
        path = self.psk()
        args = self.args('--mode', 'aware', '--passphrase-file', str(path))
        words = shlex.split(lab.native_argv(args, 'B')[-1])
        self.assertEqual(words[words.index('passphrase') + 1], "lab '$(id); password")
        self.assertEqual(words[words.index('neighbors') + 1], 'A,C')
        self.assertEqual(words[words.index('seconds') + 1], '180')
        self.assertEqual(lab.native_argv(args, 'B')[:4], ['adb', '-s', 'USB_B', 'shell'])
        self.assertNotIn('passphrase', repr(lab.public_config(args)))
        self.assertNotIn("$(id)", repr(lab.public_config(args)))

    def test_psk_rejects_missing_unused_nonascii_controls_and_bounds(self):
        with self.assertRaises(SystemExit):
            self.args('--mode', 'aware')
        with self.assertRaises(SystemExit):
            self.args('--passphrase-file', str(self.psk()))
        for value in (b'short', b'a' * 64, b'a' * 8 + b'\n', b'password\x00', b'password\xff'):
            with self.subTest(value=value), self.assertRaises(SystemExit):
                self.args('--mode', 'aware', '--passphrase-file', str(self.psk(value)))

    def test_generated_adb_commands_only_target_selected_devices_and_use_no_data_tunnel(self):
        args = self.args()
        for label, serial in zip('ABC', ['USB_A', 'USB_B', 'USB_C']):
            for argv in (lab.core_argv(args, label), lab.native_argv(args, label)):
                self.assertEqual(argv[:4], ['adb', '-s', serial, 'shell'])
                words = shlex.split(argv[-1])
                self.assertFalse(set(words) & {'forward', 'reverse', 'install', 'pm', 'svc', 'tether', 'tcpip', 'connect'})
                self.assertNotIn('--peer', words)

    def test_probe_requires_selected_authorized_usb_devices(self):
        raw = (b'List of devices attached\nUSB_A device usb:1-1 product:x\n'
               b'USB_B device usb:1-2 product:x\nUSB_C device usb:1-3 product:x\n')
        self.assertEqual(set(lab.parse_devices(raw, self.args())), set('ABC'))
        for changed in (b'List of devices attached\n', raw.replace(b'USB_B device', b'USB_B unauthorized'),
                        raw.replace(b'usb:1-3', b'transport_id:3'), raw + b'USB_A device usb:1-4\n',
                        b'\xff', b'x' * (lab.MAX_PROBE + 1)):
            with self.subTest(raw=changed[:70]), self.assertRaises(lab.LabError):
                lab.parse_devices(changed, self.args())

    def test_core_json_bounds_fail_closed_without_echoing_untrusted_input(self):
        self.assertEqual(lab.parse_core_line(b'{"event":"queued","id":"ok"}\n')['id'], 'ok')
        for raw in (b'{"body":"PRIVATE"}', b'[]', b'{"event":"x","n":NaN}', b'{"event":"x","n":1e999}',
                    b'{"event":"x","event":"y"}', b'\xff', b'{' + b' ' * lab.MAX_LINE,
                    ('{"event":"x","nested":' + '[' * 20 + '0' + ']' * 20 + '}').encode()):
            with self.subTest(raw=raw[:60]), self.assertRaises(lab.LabError) as raised:
                lab.parse_core_line(raw)
            self.assertNotIn('PRIVATE', str(raised.exception))

    def test_native_parser_accepts_only_status_stream_schema_and_ignores_raw_chatter(self):
        event = dict(schema=1, event='ble_tx', mode='ble', peer='B', reason='att_ack',
                     elapsed_ms=1, tx_bytes=4, rx_bytes=3, dropped_events=0)
        prefix = b'INSTRUMENTATION_STATUS: stream='
        self.assertEqual(lab.parse_native_line(prefix + json.dumps(event).encode()), event)
        for raw in (b'PRIVATE raw stderr', json.dumps(event).encode(), b'INSTRUMENTATION_CODE: -1',
                    b'INSTRUMENTATION_STATUS: other=PRIVATE'):
            self.assertIsNone(lab.parse_native_line(raw))
        for changed in (dict(event, body='PRIVATE'), dict(event, peer='12:34:56:78:90:ab'),
                        dict(event, reason='192.0.2.1'), dict(event, tx_bytes=-1),
                        dict(event, mode='wifi'), dict(event, schema=2), dict(event, dropped_events=-1)):
            with self.subTest(event=changed), self.assertRaises(lab.LabError) as raised:
                lab.parse_native_line(prefix + json.dumps(changed).encode())
            self.assertNotIn('PRIVATE', str(raised.exception))
        with self.assertRaises(lab.LabError):
            lab.parse_native_line(prefix + b'{malformed')

    def test_export_discards_bodies_addresses_unknown_fields_and_nested_secrets(self):
        event = dict(event='message', id='a' * 64, body='PRIVATE', address='192.0.2.1',
                     path=['b' * 64, 'c' * 64], injected=dict(body='SECRET'))
        exported = lab.export_core(event)
        self.assertEqual(exported['id'], event['id'])
        self.assertEqual(exported['path'], event['path'])
        self.assertTrue(exported['body_redacted'])
        for private in ('PRIVATE', 'SECRET', '192.0.2.1', 'injected'):
            self.assertNotIn(private, json.dumps(exported))

    def test_event_wait_preserves_interleaved_and_timed_out_evidence(self):
        events = lab.EventBuffer()
        events.push(dict(event='duplicate', id='older'))
        events.push(dict(event='message', id='later'))
        self.assertEqual(events.wait('message', .1)['id'], 'later')
        with self.assertRaises(lab.LabError):
            events.wait('absent', .01)
        self.assertEqual(events.wait('duplicate', .1)['id'], 'older')
        self.assertEqual(len(events.snapshot()), 2)

    def test_absence_checks_the_full_interval_and_does_not_consume_events(self):
        events = lab.EventBuffer()
        worker = threading.Thread(target=lambda: (time.sleep(.03), events.push(dict(event='message', id='blocked'))))
        worker.start()
        with self.assertRaises(lab.LabError):
            events.assert_absent('message', .2, lambda e: e['id'] == 'blocked')
        worker.join()
        self.assertEqual(events.wait('message', .1)['id'], 'blocked')

    def test_event_buffer_limit_is_not_silently_truncated(self):
        events = lab.EventBuffer()
        for _ in range(lab.MAX_EVENTS):
            events.push(dict(event='status'))
        with self.assertRaises(lab.LabError):
            events.push(dict(event='status'))
        with self.assertRaises(lab.LabError):
            events.wait('status', .01)
        self.assertEqual(len(events.snapshot()), lab.MAX_EVENTS)

    def test_path_check_requires_full_identity_and_fresh_full_id(self):
        keys = dict(A='a' * 64, B='b' * 64, C='c' * 64)
        event = dict(event='message', id='d' * 64, path=[keys[x] for x in 'ABC'])
        self.assertEqual(lab.checked_path(event, 'd' * 64, 'ABC', keys), ['A', 'B', 'C'])
        for changed in (dict(event, id='d' * 8), dict(event, path=[keys['A'], keys['C']]),
                        dict(event, path=[keys['A'], keys['B'][:8], keys['C']])):
            with self.assertRaises(lab.LabError):
                lab.checked_path(changed, 'd' * 64, 'ABC', keys)

    def test_real_child_reader_and_quit_confirm_cleanup_without_stderr_disclosure(self):
        script = '''import json,sys
print(json.dumps(dict(event='ready',key='a'*64)),flush=True)
print('PRIVATE STDERR',file=sys.stderr,flush=True)
for line in sys.stdin:
    if json.loads(line)['cmd']=='quit': break
'''
        child = lab.EventProcess([sys.executable, '-u', '-c', script])
        self.addCleanup(child.abort)
        self.assertEqual(child.events.wait('ready', 2)['key'], 'a' * 64)
        cleanup = child.close_core(2)
        self.assertTrue(cleanup['remote_cleanup_confirmed'])
        self.assertNotIn('PRIVATE', json.dumps(cleanup))

    def test_real_child_reader_bounds_overlong_lines(self):
        script = "import sys; print('PRIVATE'+'x'*40000,flush=True)"
        child = lab.EventProcess([sys.executable, '-u', '-c', script])
        self.addCleanup(child.abort)
        with self.assertRaises(lab.LabError) as raised:
            child.events.wait('ready', 2)
        self.assertNotIn('PRIVATE', str(raised.exception))

    def test_native_cleanup_drains_final_events_and_requires_instrumentation_success(self):
        event = dict(schema=1, event='run.end', mode='ble', peer='A', reason='device_acceptance_requires_logs',
                     elapsed_ms=1, tx_bytes=0, rx_bytes=0, dropped_events=12)
        for code, expected in ((-1, True), (0, False)):
            script = "print('INSTRUMENTATION_STATUS: stream=' + " + repr(json.dumps(event)) + "); print('INSTRUMENTATION_CODE: " + str(code) + "')"
            child = lab.EventProcess([sys.executable, '-u', '-c', script], native=True)
            self.addCleanup(child.abort)
            cleanup = child.close_native(2)
            self.assertTrue(cleanup['remote_cleanup_confirmed'])
            self.assertEqual(cleanup['instrumentation_success'], expected)
            self.assertEqual(child.events.snapshot()[-1]['dropped_events'], 12)

    def test_terminal_aware_failure_cannot_become_instrumentation_success(self):
        for reason in ('aware_permission_or_registration_failed', 'aware_attach_retry_exhausted'):
            failure = dict(schema=1, event='terminal_failure', mode='aware', peer='', reason=reason,
                           elapsed_ms=1, tx_bytes=0, rx_bytes=0, dropped_events=0)
            end = dict(failure, event='run.end', peer='A', reason='device_acceptance_requires_logs')
            script = "print('INSTRUMENTATION_STATUS: stream=' + " + repr(json.dumps(failure)) + "); print('INSTRUMENTATION_STATUS: stream=' + " + repr(json.dumps(end)) + "); print('INSTRUMENTATION_CODE: -1')"
            child = lab.EventProcess([sys.executable, '-u', '-c', script], native=True)
            self.addCleanup(child.abort)
            cleanup = child.close_native(2)
            self.assertTrue(cleanup['remote_cleanup_confirmed'])
            self.assertFalse(cleanup['instrumentation_success'])

    def test_reader_error_cannot_become_successful_cleanup(self):
        script = "import json,sys; print(json.dumps(dict(event='ready')),flush=True); print('PRIVATE malformed',flush=True); sys.stdin.readline()"
        child = lab.EventProcess([sys.executable, '-u', '-c', script])
        self.addCleanup(child.abort)
        time.sleep(.03)
        cleanup = child.close_core(2)
        self.assertFalse(cleanup['stream_integrity_confirmed'])
        self.assertNotIn('PRIVATE', json.dumps(cleanup))

    def test_full_absence_window_cannot_be_shortened_by_deadline(self):
        deadline = lab.Deadline(.01)
        with self.assertRaises(lab.LabError):
            deadline.require_interval(.02)

    def test_full_experiment_host_fixture_checks_controls_and_redacts_evidence(self):
        # The fixture is only a controller protocol test; it supplies no radio evidence.
        fixture = LabFixture()
        args = self.args('--messages-per-side', '2', '--output', str(Path(self.temp.name) / 'evidence.json'))
        with patch.object(lab, '_probe_devices', return_value={label: dict(transport='fixture') for label in 'ABC'}), \
             patch.object(lab, 'EventProcess', side_effect=fixture.process), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(lab.run(args), 0)
        evidence = json.loads(Path(args.output).read_text())
        self.assertEqual(evidence['result'], 'native_data_path_checks_passed')
        self.assertTrue(evidence['checks']['relay_off']['target_absent_full_window'])
        self.assertEqual(evidence['checks']['relay_off']['ttl_seconds'], 2)
        self.assertEqual([p['path'] for p in evidence['checks']['bidirectional_full_ids_and_paths']],
                         [list('ABC'), list('CBA'), list('ABC'), list('CBA'), list('ABC')])
        self.assertEqual(fixture.sent['A'], 4)
        self.assertEqual(fixture.sent['C'], 2)
        self.assertNotIn('radio-lab Emoji', json.dumps(evidence))
        self.assertNotIn('127.0.0.1', json.dumps(evidence))
        self.assertEqual(evidence['hardware_acceptance'], 'pending_operator_topology_and_radio_lifecycle_report')

    def test_late_duplicate_or_expired_relay_off_delivery_fails_after_draining(self):
        for kind in ('duplicate', 'blocked'):
            with self.subTest(kind=kind):
                fixture = LabFixture()
                original = FixtureProcess.close_core
                def close_late(child, timeout):
                    if child.label == 'C':
                        if kind == 'duplicate':
                            late = dict(next(e for e in child.events.snapshot() if e['event'] == 'message'))
                        else:
                            mid = next(e['id'] for e in fixture.cores['A'].events.snapshot() if e['event'] == 'expired')
                            late = dict(event='message', id=mid, path=[fixture.keys[x] for x in 'ABC'])
                        child.events.push(late)
                    return original(child, timeout)
                args = self.args('--messages-per-side', '2', '--output', str(Path(self.temp.name) / (kind+'.json')))
                with patch.object(lab, '_probe_devices', return_value={}), patch.object(lab, 'EventProcess', side_effect=fixture.process), \
                     patch.object(FixtureProcess, 'close_core', close_late), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(lab.run(args), 1)
                evidence = json.loads(Path(args.output).read_text())
                self.assertEqual(evidence['result'], 'failed')
                self.assertIn(evidence['failure'], ('duplicate_display', 'expired_relay_off_id_delivered'))

    def test_deadline_and_public_evidence_keep_hardware_acceptance_pending(self):
        with self.assertRaises(lab.LabError):
            lab.Deadline(0).remaining(1)
        config = lab.public_config(self.args())
        self.assertEqual(config['hardware_acceptance'], 'pending_operator_topology_and_radio_lifecycle_report')
        self.assertIn('20', config['acceptance_limit'])
        self.assertIn('minute', config['acceptance_limit'])

    def test_default_output_preserves_independent_radio_rounds(self):
        self.assertTrue(self.args().output.endswith('/target/native-radio-ble-lab.json'))
        aware = self.args('--mode', 'aware', '--passphrase-file', str(self.psk()))
        self.assertTrue(aware.output.endswith('/target/native-radio-aware-lab.json'))


class LabFixture:
    """In-memory device protocol fixture, used only to exercise host control flow."""
    def __init__(self):
        self.cores = {}
        self.keys = {label: (str(index) * 64) for index, label in enumerate('ABC', 1)}
        self.counter = 0
        self.relay = True
        self.sent = {label: 0 for label in 'ABC'}

    def process(self, argv, native=False):
        words = shlex.split(argv[-1])
        label = words[words.index('label') + 1] if native else words[words.index('--state') + 1][-1]
        child = FixtureProcess(self, label, native)
        if native:
            if len(self.cores) == 3:
                for endpoint in 'AC':
                    self.cores[endpoint].events.push(dict(event='neighbor_up', peer=self.keys['B']))
        else:
            self.cores[label] = child
        return child


class FixtureProcess:
    def __init__(self, fixture, label, native):
        self.fixture, self.label, self.native = fixture, label, native
        self.started = time.monotonic()
        self.events = lab.EventBuffer()
        if native:
            self.events.push(dict(schema=1, event='run.start', mode='ble', peer=label,
                                  reason='hardware_validation_pending', elapsed_ms=0, tx_bytes=0, rx_bytes=0, dropped_events=0))
        else:
            self.events.push(dict(event='ready', key=fixture.keys[label], address='127.0.0.1:39770', relay=label == 'B'))

    def command(self, command, **fields):
        f, label = self.fixture, self.label
        if command == 'neighbors':
            self.events.push(dict(event='neighbors', peers=[f.keys[x] for x in ('AC' if label == 'B' else 'B')]))
        elif command == 'relay':
            f.relay = fields['enabled']
            self.events.push(dict(event='relay', enabled=f.relay))
        elif command == 'send':
            f.counter += 1
            f.sent[label] += 1
            mid = format(f.counter, '064x')
            self.events.push(dict(event='queued', id=mid))
            f.cores['B'].events.push(dict(event='message', id=mid, path=[f.keys[label], f.keys['B']], body=fields['text']))
            if f.relay:
                target = 'C' if label == 'A' else 'A'
                f.cores[target].events.push(dict(event='message', id=mid, path=[f.keys[x] for x in label + 'B' + target], body=fields['text']))
            else:
                self.events.push(dict(event='expired', id=mid))
        elif command in ('status', 'diagnostics'):
            self.events.push(dict(event=command, pending=0, received=1, connections=2 if label == 'B' else 1))

    def close_core(self, timeout):
        return dict(remote_cleanup_confirmed=True, stream_integrity_confirmed=True)

    def close_native(self, timeout):
        for peer in ('AC' if self.label == 'B' else 'B'):
            self.events.push(dict(schema=1, event='ble_link_down', mode='ble', peer=peer,
                                  reason='closed', elapsed_ms=1, tx_bytes=500, rx_bytes=500, dropped_events=0))
        return dict(remote_cleanup_confirmed=True, stream_integrity_confirmed=True, instrumentation_success=True)


if __name__ == '__main__':
    unittest.main()
