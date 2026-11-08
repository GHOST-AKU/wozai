#!/usr/bin/env python3
"""USB control-only three-Android native data-path lab; hardware acceptance stays pending.

Use an already deployed, authorized signed runner and Android arm64 Rust core.
This tool neither installs software nor changes permissions/radios or USB networking.
Aware PSKs are laboratory link credentials: am instrumentation arguments are visible
locally through adb/process inspection. Keep the PSK file private; its value is never
included in this tool's evidence or error messages. Do not reuse a production secret.
"""
import argparse
from datetime import datetime, timezone
import json
import math
from pathlib import Path, PurePosixPath
import re
import shlex
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
MAX_LINE = 16384
MAX_NATIVE_LINE = 4096
MAX_PROBE = 65536
MAX_EVENTS = 4096
MAX_STDERR = 1048576
NATIVE_SECONDS = 180
SERIAL = re.compile(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}\Z')
TOKEN = re.compile(r'[A-Za-z0-9_]{0,80}\Z')
EVENT = re.compile(r'[a-z][a-z0-9_.]{0,79}\Z')
HEX = re.compile(r'[0-9a-f]{64}\Z')


class LabError(Exception):
    """An intentionally payload-free error suitable for exported lab evidence."""


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', required=True, choices=('ble', 'aware'))
    for label in 'abc':
        parser.add_argument('--' + label, required=True, help='Explicit authorized USB serial')
    parser.add_argument('--core-path', default='/data/local/tmp/nearby-mesh-lab')
    parser.add_argument('--output', help='Evidence JSON (default: mesh/target/native-radio-MODE-lab.json)')
    parser.add_argument('--passphrase-file', help='Aware only: exactly 8–63 printable ASCII bytes, no newline')
    parser.add_argument('--messages-per-side', type=int, default=12, choices=range(2, 13))
    parser.add_argument('--timeout', type=int, default=240, help='Whole-run deadline in seconds (210–600, includes cleanup)')
    args = parser.parse_args(argv)
    if args.output is None:
        args.output = str(ROOT / ('target/native-radio-' + args.mode + '-lab.json'))
    serials = [getattr(args, label) for label in 'abc']
    if len(set(serials)) != 3 or any(not SERIAL.fullmatch(s) or s.startswith('emulator-') for s in serials):
        parser.error('three distinct bounded USB serial tokens are required')
    path = args.core_path
    if (len(path) > 200 or not re.fullmatch(r'/[A-Za-z0-9_./-]+', path)
            or any(part in ('', '.', '..') for part in path.split('/')[1:])
            or str(PurePosixPath(path)) != path):
        parser.error('core-path must be a bounded absolute path with plain path components')
    if not 210 <= args.timeout <= 600:
        parser.error('timeout must be between 210 and 600 seconds')
    args.passphrase = None
    if args.mode == 'aware':
        if not args.passphrase_file:
            parser.error('aware requires passphrase-file')
        try:
            with Path(args.passphrase_file).open('rb') as handle:
                raw = handle.read(64)
        except OSError:
            parser.error('cannot read passphrase-file')
        if not 8 <= len(raw) <= 63 or any(byte < 32 or byte > 126 for byte in raw):
            parser.error('passphrase must contain exactly 8–63 printable ASCII bytes without newline')
        args.passphrase = raw.decode('ascii')
    elif args.passphrase_file is not None:
        parser.error('passphrase-file is valid only for aware')
    return args


def _adb_shell(args, label, words):
    # adb shell reparses its arguments. Send exactly one properly quoted command.
    return ['adb', '-s', getattr(args, label.lower()), 'shell', shlex.join(words)]


def core_argv(args, label):
    if label not in 'ABC' or len(label) != 1:
        raise LabError('invalid_label')
    words = ['exec', args.core_path, 'run', '--state',
             '/data/local/tmp/nearby-mesh-radio/' + args.mode + '/' + label,
             '--listen', '127.0.0.1:39770']
    if label == 'B':
        words.append('--relay')
    return _adb_shell(args, label, words)


def native_argv(args, label):
    if label not in 'ABC' or len(label) != 1:
        raise LabError('invalid_label')
    words = ['am', 'instrument', '-w', '-r', '-e', 'mode', args.mode,
             '-e', 'label', label, '-e', 'neighbors', 'A,C' if label == 'B' else 'B',
             '-e', 'core_port', '39770', '-e', 'seconds', str(NATIVE_SECONDS)]
    if args.mode == 'aware':
        words += ['-e', 'passphrase', args.passphrase]
    words.append('org.nearbymesh.radio/org.nearbymesh.radio.RadioLab')
    return _adb_shell(args, label, words)


def public_config(args):
    return dict(mode=args.mode, labels=list('ABC'), core_path=args.core_path,
                messages_per_side=args.messages_per_side, control='USB adb only; no forwarding or radio changes',
                validation='native data path test only',
                hardware_acceptance='pending_operator_topology_and_radio_lifecycle_report',
                acceptance_limit='Full 20-message-per-origin acceptance requires a minute-window roll; this run is capped at 12 plus controls.',
                pending_checks=['physical_A_C_isolation', 'no_common_AP_or_Internet', 'actual_link_loss',
                                'permission_revocation', 'restart', 'manual_radio_off_and_recovery',
                                '20_messages_each_direction_per_mode', 'device_compatibility_power_background'])


def parse_devices(raw, args):
    if len(raw) > MAX_PROBE:
        raise LabError('device_probe_bound')
    try:
        lines = raw.decode('utf-8', errors='strict').splitlines()
    except UnicodeError:
        raise LabError('device_probe_encoding') from None
    if not lines or lines[0].strip() != 'List of devices attached':
        raise LabError('device_probe_format')
    found = {}
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) < 2 or len(fields) > 20 or any(len(field) > 256 for field in fields):
            raise LabError('device_probe_format')
        serial = fields[0]
        if serial in found:
            raise LabError('device_probe_duplicate')
        found[serial] = fields
    selected = {}
    for label in 'ABC':
        fields = found.get(getattr(args, label.lower()))
        if not fields or fields[1] != 'device' or not any(re.fullmatch(r'usb:[A-Za-z0-9_.:-]{1,80}', item) for item in fields[2:]):
            raise LabError('selected_device_missing_unauthorized_or_not_usb')
        selected[label] = dict(authorized=True, transport='usb')
    return selected


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise LabError('json_duplicate_field')
        value[key] = item
    return value


def _bounded_value(value, depth=0):
    if depth > 8:
        raise LabError('json_depth_bound')
    if isinstance(value, dict):
        if len(value) > 64 or any(len(key) > 80 for key in value):
            raise LabError('json_object_bound')
        for item in value.values():
            _bounded_value(item, depth + 1)
    elif isinstance(value, list):
        if len(value) > 64:
            raise LabError('json_array_bound')
        for item in value:
            _bounded_value(item, depth + 1)
    elif isinstance(value, str):
        if len(value.encode('utf-8')) > 8192:
            raise LabError('json_string_bound')
    elif isinstance(value, float) and not math.isfinite(value):
        raise LabError('json_number')
    elif value is not None and not isinstance(value, (bool, int, float)):
        raise LabError('json_value_type')


def parse_core_line(raw):
    if len(raw) > MAX_LINE:
        raise LabError('json_line_bound')
    try:
        value = json.loads(raw.decode('utf-8', errors='strict'), object_pairs_hook=_unique_object,
                           parse_constant=lambda _: (_ for _ in ()).throw(LabError('json_number')))
        _bounded_value(value)
    except (ValueError, UnicodeError, RecursionError, OverflowError):
        raise LabError('invalid_json_event') from None
    if not isinstance(value, dict) or not isinstance(value.get('event'), str) or not EVENT.fullmatch(value['event']):
        raise LabError('invalid_json_event')
    return value


def parse_native_line(raw):
    if len(raw) > MAX_NATIVE_LINE:
        raise LabError('native_line_bound')
    prefix = b'INSTRUMENTATION_STATUS: stream='
    if not raw.startswith(prefix):
        return None
    payload = raw[len(prefix):].strip()
    if not payload:
        return None
    value = parse_core_line(payload)
    fields = {'schema', 'event', 'mode', 'peer', 'reason', 'elapsed_ms', 'tx_bytes', 'rx_bytes', 'dropped_events'}
    if (set(value) != fields or type(value['schema']) is not int or value['schema'] != 1
            or value['mode'] not in ('ble', 'aware') or value['peer'] not in ('', 'A', 'B', 'C')
            or not isinstance(value['reason'], str) or not TOKEN.fullmatch(value['reason'])
            or any(type(value[key]) is not int or not 0 <= value[key] <= 2**63 - 1
                   for key in ('elapsed_ms', 'tx_bytes', 'rx_bytes', 'dropped_events'))):
        raise LabError('invalid_native_metadata')
    return value


def export_core(event):
    # An allowlist keeps unknown/raw platform fields out of evidence as well as bodies.
    out = {'event': event['event']}
    if 'body' in event:
        out['body_redacted'] = True
    for field in ('id', 'peer', 'key'):
        value = event.get(field)
        if isinstance(value, str) and HEX.fullmatch(value):
            out[field] = value
    path = event.get('path')
    if isinstance(path, list) and len(path) <= 8 and all(isinstance(k, str) and HEX.fullmatch(k) for k in path):
        out['path'] = path
    for field in ('relay', 'enabled', 'public_plaintext', 'pending', 'seen', 'connections', 'candidates',
                  'received', 'duplicates', 'rejected', 'handed_to_neighbor_unconfirmed', 'bytes_rx',
                  'bytes_tx', 'peak_connections', 'peak_pending', 'peak_tx_frames_per_link', 'count'):
        if type(event.get(field)) in (bool, int) and abs(event[field]) <= 2**64:
            out[field] = event[field]
    for field in ('state', 'reason', 'authorization', 'hardware_validation'):
        value = event.get(field)
        if isinstance(value, str) and TOKEN.fullmatch(value):
            out[field] = value
    return out


class EventBuffer:
    """Bounded history plus independent pending events: waits preserve interleaving."""
    def __init__(self):
        self._events = []
        self._pending = []
        self._condition = threading.Condition()
        self._error = None
        self._closed = False

    def push(self, event):
        with self._condition:
            if len(self._events) >= MAX_EVENTS:
                self._error = 'event_buffer_bound'
                self._condition.notify_all()
                raise LabError(self._error)
            self._pending.append((len(self._events), event))
            self._events.append(event)
            self._condition.notify_all()

    def finish(self, error=None):
        with self._condition:
            self._closed = True
            self._error = self._error or error
            self._condition.notify_all()

    def mark(self):
        with self._condition:
            return len(self._events)

    def snapshot(self):
        with self._condition:
            return list(self._events)

    def healthy(self):
        with self._condition:
            return self._error is None

    def _check(self):
        if self._error:
            raise LabError(self._error)

    def wait(self, event, timeout, predicate=lambda item: True, after=0):
        end = time.monotonic() + timeout
        with self._condition:
            while True:
                self._check()
                for index, (sequence, item) in enumerate(self._pending):
                    if sequence >= after and item.get('event') == event and predicate(item):
                        self._pending.pop(index)
                        return item
                if self._closed:
                    raise LabError('stream_closed_before_' + event)
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise LabError('missing_' + event)
                self._condition.wait(min(.2, remaining))

    def assert_absent(self, event, timeout, predicate=lambda item: True, after=0):
        end = time.monotonic() + timeout
        with self._condition:
            cursor = after
            while True:
                self._check()
                for item in self._events[cursor:]:
                    if item.get('event') == event and predicate(item):
                        raise LabError('unexpected_' + event)
                cursor = len(self._events)
                if self._closed:
                    raise LabError('stream_closed_during_absence_window')
                remaining = end - time.monotonic()
                if remaining <= 0:
                    return
                self._condition.wait(min(.2, remaining))


class EventProcess:
    def __init__(self, argv, native=False):
        self.native = native
        self.events = EventBuffer()
        self.stderr_bytes = 0
        self.instrumentation_code = None
        self.started = time.monotonic()
        try:
            self.p = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                      stderr=subprocess.PIPE, bufsize=0)
        except OSError:
            raise LabError('cannot_start_adb_or_child') from None
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.stderr_reader = threading.Thread(target=self._discard_stderr, daemon=True)
        self.reader.start()
        self.stderr_reader.start()

    def _read(self):
        error = None
        limit = MAX_NATIVE_LINE if self.native else MAX_LINE
        try:
            total = 0
            while True:
                line = self.p.stdout.readline(limit + 1)
                if not line:
                    break
                total += len(line)
                if len(line) > limit or total > MAX_LINE * MAX_EVENTS:
                    raise LabError('stdout_bound')
                if self.native and line.startswith(b'INSTRUMENTATION_CODE:'):
                    match = re.fullmatch(rb'INSTRUMENTATION_CODE: (-?[0-9]{1,4})\r?\n?', line)
                    if not match or self.instrumentation_code is not None:
                        raise LabError('invalid_instrumentation_result')
                    self.instrumentation_code = int(match.group(1))
                event = parse_native_line(line) if self.native else parse_core_line(line)
                if event is not None:
                    self.events.push(event)
        except LabError as failure:
            error = str(failure)
        except (OSError, ValueError):
            error = 'stdout_read_failed'
        finally:
            self.events.finish(error)

    def _discard_stderr(self):
        try:
            while True:
                block = self.p.stderr.read(4096)
                if not block:
                    return
                self.stderr_bytes += len(block)
                if self.stderr_bytes > MAX_STDERR:
                    self.events.finish('stderr_bound')
                    return
        except (OSError, ValueError):
            pass

    def command(self, command, **fields):
        try:
            data = (json.dumps(dict(cmd=command, **fields), ensure_ascii=False) + '\n').encode('utf-8')
            if len(data) > MAX_LINE:
                raise LabError('command_bound')
            self.p.stdin.write(data)
            self.p.stdin.flush()
        except (BrokenPipeError, OSError, ValueError):
            raise LabError('core_control_closed') from None

    def _close_local(self):
        # The child has exited; drain its final metadata before closing read handles.
        self.reader.join(timeout=1)
        self.stderr_reader.join(timeout=1)
        for pipe in (self.p.stdin, self.p.stdout, self.p.stderr):
            try:
                pipe.close()
            except (OSError, ValueError):
                pass
        self.reader.join(timeout=1)
        self.stderr_reader.join(timeout=1)

    def abort(self):
        if self.p.poll() is None:
            self.p.terminate()
            try:
                self.p.wait(timeout=1)
            except subprocess.TimeoutExpired:
                self.p.kill()
                self.p.wait(timeout=1)
        self._close_local()

    def close_core(self, timeout=5):
        requested = False
        if self.p.poll() is None:
            try:
                self.command('quit')
                requested = True
            except LabError:
                pass
        try:
            self.p.wait(timeout=max(.01, timeout))
        except subprocess.TimeoutExpired:
            self.abort()
        else:
            self._close_local()
        return dict(remote_cleanup_confirmed=requested and self.p.returncode == 0,
                    method='core_quit_and_adb_exec_exit', adb_exit_code=self.p.returncode,
                    stream_integrity_confirmed=self.events.healthy() and not self.reader.is_alive(),
                    stderr_bytes_discarded=self.stderr_bytes)

    def close_native(self, timeout):
        try:
            self.p.wait(timeout=max(.01, timeout))
        except subprocess.TimeoutExpired:
            self.abort()
        else:
            self._close_local()
        history = self.events.snapshot()
        confirmed = (self.p.returncode == 0 and any(e['event'] == 'run.end' for e in history)
                     and not any(e['event'] in ('cleanup.timeout', 'cleanup.error') for e in history))
        fatal = any(e['event'] in ('run.error', 'unsupported', 'terminal_failure')
                    or (e['event'] == 'ble_error' and not e['peer']) for e in history)
        return dict(remote_cleanup_confirmed=confirmed, method='bounded_instrumentation_lifetime_and_run_end',
                    instrumentation_success=self.instrumentation_code == -1 and not fatal,
                    stream_integrity_confirmed=self.events.healthy() and not self.reader.is_alive(),
                    adb_exit_code=self.p.returncode, stderr_bytes_discarded=self.stderr_bytes)


class Deadline:
    def __init__(self, seconds):
        self.end = time.monotonic() + seconds

    def remaining(self, cap):
        left = self.end - time.monotonic()
        if left <= 0:
            raise LabError('whole_run_deadline')
        return min(left, cap)

    def require_interval(self, seconds):
        if self.end - time.monotonic() < seconds:
            raise LabError('insufficient_deadline_for_full_observation_window')
        return seconds


def checked_path(event, message_id, direction, keys):
    if (not isinstance(message_id, str) or not HEX.fullmatch(message_id)
            or event.get('id') != message_id or event.get('path') != [keys[label] for label in direction]):
        raise LabError('full_id_or_authenticated_path_mismatch')
    return list(direction)


def _probe_devices(args, deadline):
    try:
        process = subprocess.Popen(['adb', 'devices', '-l'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError:
        raise LabError('cannot_start_adb') from None
    data = bytearray()
    error = []
    def read():
        try:
            while True:
                chunk = process.stdout.read(4096)
                if not chunk:
                    break
                data.extend(chunk)
                if len(data) > MAX_PROBE:
                    error.append('device_probe_bound')
                    break
        except OSError:
            error.append('device_probe_read')
    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    try:
        process.wait(timeout=deadline.remaining(10))
        reader.join(timeout=1)
        if process.returncode != 0 or reader.is_alive() or error:
            raise LabError(error[0] if error else 'device_probe_failed')
        return parse_devices(bytes(data), args)
    except subprocess.TimeoutExpired:
        raise LabError('device_probe_timeout') from None
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=1)
        process.stdout.close()
        reader.join(timeout=1)


def _fresh_command(node, command, event, deadline, predicate=lambda item: True, **fields):
    after = node.events.mark()
    node.command(command, **fields)
    return node.events.wait(event, deadline.remaining(20), predicate, after)


def _native_summary(native, mode):
    peers = {}
    for event in native.events.snapshot():
        if event['mode'] != mode:
            raise LabError('native_mode_mismatch')
        if event['peer'] in 'ABC' and event['peer']:
            counters = peers.setdefault(event['peer'], dict(tx_bytes=0, rx_bytes=0))
            for field in counters:
                counters[field] = max(counters[field], event[field])
    return peers


def run(args):
    began = time.monotonic()
    deadline = Deadline(args.timeout)
    evidence = dict(schema=1, **public_config(args), started_at_utc=datetime.now(timezone.utc).isoformat(),
                    result='failed', checks={}, devices={}, cleanup={}, core_events={}, native_events={})
    cores = {}
    natives = {}
    failure = None
    try:
        evidence['devices'] = _probe_devices(args, deadline)
        keys = {}
        for label in 'BAC':
            core = cores[label] = EventProcess(core_argv(args, label))
            ready = core.events.wait('ready', deadline.remaining(30))
            key = ready.get('key')
            if (not isinstance(key, str) or not HEX.fullmatch(key) or key in keys.values()
                    or ready.get('address') != '127.0.0.1:39770' or ready.get('relay') is not (label == 'B')):
                raise LabError('core_identity_listen_or_relay_mismatch')
            keys[label] = key
        for label in 'BAC':
            native = natives[label] = EventProcess(native_argv(args, label), native=True)
            native.events.wait('run.start', deadline.remaining(20), lambda e: e['mode'] == args.mode and e['peer'] == label)
        # One shared connection deadline bounds both endpoint waits and B's second adjacency.
        connect = Deadline(deadline.remaining(60))
        for label in 'AC':
            cores[label].events.wait('neighbor_up', connect.remaining(60), lambda e: e.get('peer') == keys['B'])
        for label in 'ABC':
            status = _fresh_command(cores[label], 'neighbors', 'neighbors', deadline)
            expected = [keys[x] for x in ('AC' if label == 'B' else 'B')]
            if sorted(status.get('peers', [])) != sorted(expected):
                raise LabError('configured_authenticated_adjacencies_mismatch')
        evidence['checks']['authenticated_simultaneous_B_neighbors'] = True
        ids = set()
        paths = []
        def deliver(source, target, text, phase):
            target_mark = cores[target].events.mark()
            queued = _fresh_command(cores[source], 'send', 'queued', deadline, text=text, ttl=30)
            mid = queued.get('id')
            if not isinstance(mid, str) or not HEX.fullmatch(mid) or mid in ids:
                raise LabError('message_id_not_fresh')
            ids.add(mid)
            received = cores[target].events.wait('message', deadline.remaining(20), lambda e: e.get('id') == mid, target_mark)
            path = checked_path(received, mid, source + 'B' + target, keys)
            if received.get('body') != text:
                raise LabError('received_payload_mismatch')
            paths.append(dict(id=mid, path=path, phase=phase))
        for index in range(args.messages_per_side):
            # Each side includes exactly 2048 UTF-8 bytes and a separate Emoji sample.
            text = 'x' * 2048 if index == 0 else ('radio-lab Emoji 🌐🔒 ' + str(index))
            deliver('A', 'C', text, 'bidirectional')
            deliver('C', 'A', text, 'bidirectional')
        evidence['checks']['bidirectional_full_ids_and_paths'] = paths
        _fresh_command(cores['B'], 'relay', 'relay', deadline, lambda e: e.get('enabled') is False, enabled=False)
        target_mark = cores['C'].events.mark()
        relay_mark = cores['B'].events.mark()
        off_started = time.monotonic()
        queued = _fresh_command(cores['A'], 'send', 'queued', deadline, text='radio-lab relay-off control', ttl=2)
        blocked = queued.get('id')
        if not isinstance(blocked, str) or not HEX.fullmatch(blocked) or blocked in ids:
            raise LabError('blocked_message_id_not_fresh')
        ids.add(blocked)
        relay_seen = cores['B'].events.wait('message', deadline.remaining(10), lambda e: e.get('id') == blocked, relay_mark)
        checked_path(relay_seen, blocked, 'AB', keys)
        # Start after confirmed B-off; inspect history too, then observe beyond the full TTL.
        cores['C'].events.assert_absent('message', deadline.require_interval(max(0, 2.5 - (time.monotonic() - off_started))),
                                        lambda e: e.get('id') == blocked, target_mark)
        cores['A'].events.wait('expired', deadline.remaining(5), lambda e: e.get('id') == blocked)
        _fresh_command(cores['C'], 'status', 'status', deadline)
        cores['C'].events.assert_absent('message', 0, lambda e: e.get('id') == blocked, target_mark)
        evidence['checks']['relay_off'] = dict(id=blocked, ttl_seconds=2, B_saw=True,
                                              target_absent_full_window=True, origin_expired=True)
        _fresh_command(cores['B'], 'relay', 'relay', deadline, lambda e: e.get('enabled') is True, enabled=True)
        deliver('A', 'C', 'radio-lab relay restored', 'relay_reenabled')
        evidence['diagnostics'] = {label: export_core(_fresh_command(core, 'diagnostics', 'diagnostics', deadline))
                                   for label, core in cores.items()}
        for core in cores.values():
            displayed = [e.get('id') for e in core.events.snapshot() if e['event'] == 'message']
            if len(displayed) != len(set(displayed)):
                raise LabError('duplicate_display')
        evidence['checks']['no_duplicate_display'] = True
    except LabError as error:
        failure = str(error)
    except (OSError, ValueError, KeyError, TypeError):
        failure = 'controller_io_or_event_shape_failure'
    except KeyboardInterrupt:
        failure = 'operator_interrupted'
    finally:
        for label, core in cores.items():
            evidence['cleanup']['core_' + label] = core.close_core(max(.01, min(5, deadline.end - time.monotonic())))
        # There is intentionally no force-stop, pkill, permission change or radio control.
        # Wait only for each runner's declared lifetime, bounded by the whole-run deadline.
        for label, native in natives.items():
            until_expiry = native.started + NATIVE_SECONDS + 8 - time.monotonic()
            evidence['cleanup']['native_' + label] = native.close_native(max(.01, min(until_expiry, deadline.end - time.monotonic())))
        for label, core in cores.items():
            evidence['core_events'][label] = [export_core(event) for event in core.events.snapshot()]
        for label, native in natives.items():
            evidence['native_events'][label] = native.events.snapshot()
    try:
        if len(natives) == 3:
            per_link = {label: _native_summary(native, args.mode) for label, native in natives.items()}
            evidence['native_per_link_bytes'] = per_link
            evidence['native_byte_counter_scope'] = 'observed per-peer maxima; telemetry can be capped and is not a throughput measurement'
            for label in 'ABC':
                for peer in ('AC' if label == 'B' else 'B'):
                    counts = per_link[label].get(peer, {})
                    if counts.get('tx_bytes', 0) <= 0 or counts.get('rx_bytes', 0) <= 0:
                        raise LabError('native_per_link_bytes_missing')
            evidence['checks']['native_each_link_bytes_observed'] = True
        # Judge the complete drained histories, including events emitted during shutdown.
        for history in evidence['core_events'].values():
            displayed = [e.get('id') for e in history if e['event'] == 'message']
            if len(displayed) != len(set(displayed)):
                raise LabError('duplicate_display')
        blocked = evidence['checks'].get('relay_off', {}).get('id')
        if blocked and any(e['event'] == 'message' and e.get('id') == blocked
                           for e in evidence['core_events'].get('C', [])):
            raise LabError('expired_relay_off_id_delivered')
        if any(not check['remote_cleanup_confirmed'] for check in evidence['cleanup'].values()):
            raise LabError('remote_cleanup_unconfirmed')
        if any(not check['stream_integrity_confirmed'] for check in evidence['cleanup'].values()):
            raise LabError('output_stream_integrity_unconfirmed')
        if any(not check['instrumentation_success'] for name, check in evidence['cleanup'].items() if name.startswith('native_')):
            raise LabError('instrumentation_did_not_report_success')
        if len(cores) != 3 or len(natives) != 3:
            raise LabError('three_device_native_run_incomplete')
    except LabError as error:
        failure = failure or str(error)
    evidence['elapsed_seconds'] = round(time.monotonic() - began, 3)
    evidence['result'] = 'failed' if failure else 'native_data_path_checks_passed'
    if failure:
        evidence['failure'] = failure
    output = Path(args.output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(evidence=str(output), result=evidence['result'],
                          hardware_acceptance=evidence['hardware_acceptance']), ensure_ascii=False))
    return 1 if failure else 0


def main(argv=None):
    args = parse_args(argv)
    try:
        return run(args)
    except (LabError, OSError):
        print('radio-lab: controller failed; evidence output unavailable', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
