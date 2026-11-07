"""Experimental USB-HCI LE CoC client for Android preview.6; never uses NIM4."""
import argparse
import asyncio
from contextlib import suppress
import json
import logging
import re
import secrets
import time
import uuid

SERVICE = uuid.UUID('eed3c891-5c83-4f9c-a88d-94bc822c71e0').bytes[::-1]
LIMIT = 32768


def parse_offer(data):
    """Parse raw AD structures, including Android's scan-response service data."""
    offset = 0
    while offset < len(data):
        size = data[offset]
        if size == 0:
            return None
        end = offset + size + 1
        if end > len(data):
            return None
        field = data[offset + 1:end]
        offset = end
        if field[:1] != b'\x21' or field[1:17] != SERVICE:
            continue
        offer = field[17:]
        if not 5 <= len(offer) <= 12 or offer[0] != 1 or len(offer) != offer[1] + 4:
            return None
        name = offer[2:-2]
        psm = int.from_bytes(offer[-2:], 'big')
        if not re.fullmatch(rb'[A-Za-z0-9_-]{1,8}', name) or not psm:
            return None
        return name.decode('ascii'), psm
    return None


class ChannelStream:
    """Bound application ingress; upstream pause_reading is a no-op in this version."""
    def __init__(self, channel):
        self.channel = channel
        self.buffer = bytearray()
        self.changed = asyncio.Event()
        self.closed = False
        self.error = None
        self.tx = self.rx = 0
        channel.sink = self.receive
        channel.on('close', self.eof)

    def receive(self, data):
        if self.closed:
            return
        if len(self.buffer) + len(data) > LIMIT:
            self.fail('ingress_bound')
            return
        self.buffer.extend(data)
        self.rx += len(data)
        self.changed.set()

    def eof(self):
        self.closed = True
        self.changed.set()

    def fail(self, reason):
        self.error = reason
        self.buffer.clear()
        self.eof()

    async def read(self, count=4096):
        while not self.buffer and not self.closed:
            self.changed.clear()
            await self.changed.wait()
        if self.error:
            raise OSError(self.error)
        data = bytes(self.buffer[:count])
        del self.buffer[:count]
        return data

    async def exact(self, count):
        result = bytearray()
        while len(result) < count:
            data = await self.read(count - len(result))
            if not data:
                raise OSError('preface_eof')
            result.extend(data)
        return bytes(result)

    async def write(self, data):
        if self.closed:
            raise OSError('channel_closed')
        # One producer; do not enqueue another block before upstream credit drain.
        self.channel.write(data)
        await asyncio.wait_for(self.channel.drain(), 5)
        if self.closed:
            raise OSError('channel_closed')
        self.tx += len(data)

    async def handshake(self, local, expected):
        await self.write(b'NMLC\x01' + bytes([len(local)]) + local.encode('ascii'))
        header = await self.exact(6)
        if header[:5] != b'NMLC\x01' or not 1 <= header[5] <= 8:
            raise OSError('preface_invalid')
        if await self.exact(header[5]) != expected.encode('ascii'):
            raise OSError('preface_peer_mismatch')
        await self.write(b'R')
        if await self.exact(1) != b'R':
            raise OSError('ready_invalid')

    async def bridge(self, port):
        # Only the local Core accepts this stream. Never an IP radio substitute.
        reader, writer = await asyncio.wait_for(asyncio.open_connection('127.0.0.1', port), 3)

        async def upstream():
            while data := await reader.read(4096):
                await self.write(data)

        async def downstream():
            while data := await self.read():
                writer.write(data)
                await asyncio.wait_for(writer.drain(), 5)

        jobs = [asyncio.create_task(upstream()), asyncio.create_task(downstream())]
        try:
            done, _ = await asyncio.wait(jobs, return_when=asyncio.FIRST_COMPLETED)
            for job in done:
                job.result()
        finally:
            for job in jobs:
                job.cancel()
            await asyncio.gather(*jobs, return_exceptions=True)
            writer.close()
            with suppress(Exception):
                await asyncio.wait_for(writer.wait_closed(), 3)


class Adapter:
    def __init__(self, device, label, port, emit):
        if not re.fullmatch(r'-[A-Za-z0-9_-]{1,7}', label):
            raise ValueError('client_label_must_sort_before_android_hex_label')
        self.device, self.label, self.port, self.emit = device, label, port, emit
        self.candidates = {}
        self.tasks = {}
        self.closed = False
        self.dial_gate = asyncio.Lock()
        self.device.on('advertisement', self.observed)

    def observed(self, advertisement):
        if self.closed or advertisement.is_truncated:
            return
        offer = parse_offer(advertisement.data_bytes)
        if not offer:
            return
        label, psm = offer
        if self.label >= label:
            return
        now = time.monotonic()
        # A fresh advertisement is required after cooldown; no timers reconnect stale peers.
        for name, item in list(self.candidates.items()):
            if now - item['seen'] > 30 and name not in self.tasks and now >= item['retry']:
                del self.candidates[name]
        candidate = self.candidates.get(label)
        if candidate is None:
            if len(self.candidates) >= 16:
                return
            candidate = {'retry': 0, 'attempts': 0}
            self.candidates[label] = candidate
        candidate.update(seen=now, address=advertisement.address, psm=psm)
        if label in self.tasks or len(self.tasks) >= 4 or now < candidate['retry']:
            return
        candidate['attempts'] += 1
        self.tasks[label] = asyncio.create_task(self.session(label, candidate.copy()))

    async def session(self, label, candidate):
        connection = stream = None
        reason = 'channel_closed'
        try:
            async with self.dial_gate:
                if self.closed or time.monotonic() - candidate['seen'] > 30:
                    return
                self.emit('connecting', 'le_coc', 0, 0)
                async with asyncio.timeout(15):
                    connection = await self.device.connect(candidate['address'], timeout=10)
                    from bumble.l2cap import LeCreditBasedChannelSpec
                    channel = await connection.create_l2cap_channel(
                        LeCreditBasedChannelSpec(psm=candidate['psm'], mtu=2048, mps=2048, max_credits=8))
                    stream = ChannelStream(channel)
                    await stream.handshake(self.label, label)
            self.emit('channel_ready', 'core_auth_pending', 0, 0)
            await stream.bridge(self.port)
        except asyncio.CancelledError:
            reason = 'stopped'
            raise
        except TimeoutError:
            reason = 'operation_timeout'
        except Exception:
            # Exception strings can contain addresses or radio payloads.
            reason = stream.error if stream and stream.error else 'session_failed'
        finally:
            if stream:
                stream.eof()
                with suppress(Exception):
                    await asyncio.wait_for(stream.channel.disconnect(), 3)
            if connection:
                with suppress(Exception):
                    await asyncio.wait_for(connection.disconnect(), 3)
            current = self.candidates.get(label)
            if current:
                attempts = current['attempts']
                delay = 60 if attempts >= 4 else 2 ** (attempts - 1)
                current['retry'] = time.monotonic() + delay
                if attempts >= 4:
                    current['attempts'] = 0
            self.tasks.pop(label, None)
            self.emit('link_down', reason, stream.tx if stream else 0, stream.rx if stream else 0)

    async def close(self):
        self.closed = True
        self.device.remove_listener('advertisement', self.observed)
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.candidates.clear()


async def run(args):
    from bumble.device import Device
    from bumble.transport import open_transport
    from bumble.hci import Address
    # Prevent upstream debug logging from exporting addresses or payloads.
    logging.disable(logging.CRITICAL)
    started = time.monotonic()

    def emit(event, reason, tx, rx):
        print(json.dumps({'schema': 'nearby-mesh-windows-radio-v1', 'event': event,
                          'reason': reason, 'elapsed_ms': round((time.monotonic() - started) * 1000),
                          'tx_bytes': tx, 'rx_bytes': rx}), flush=True)

    # Explicit transport only; never change the installed Windows Bluetooth driver.
    async with await open_transport(args.transport) as transport:
        address = ':'.join(f'{b:02X}' for b in bytes([0xC0]) + secrets.token_bytes(5))
        device = Device.with_hci('Nearby Mesh lab', Address(address), transport.source, transport.sink)
        adapter = Adapter(device, args.label, args.core_port, emit)
        try:
            await asyncio.wait_for(device.power_on(), 15)
            await asyncio.wait_for(device.start_scanning(legacy=True, active=True), 5)
            emit('ready', 'usb_hci_experimental', 0, 0)
            await asyncio.sleep(args.seconds)
        finally:
            await adapter.close()
            with suppress(Exception):
                await asyncio.wait_for(device.stop_scanning(), 3)
            emit('stopped', 'closed', 0, 0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--transport', required=True, help='Explicit dedicated USB HCI transport, e.g. usb:0')
    parser.add_argument('--core-port', type=int, default=39770)
    parser.add_argument('--label', default='-' + secrets.token_hex(3))
    parser.add_argument('--seconds', type=int, default=300)
    args = parser.parse_args()
    if not args.transport.startswith('usb:') or not 1 <= args.seconds <= 3600 or not 1 <= args.core_port <= 65535:
        parser.error('require explicit usb: transport, seconds 1..3600 and port 1..65535')
    try:
        asyncio.run(run(args))
    except KeyboardInterrupt:
        pass
    except Exception:
        print(json.dumps({'schema': 'nearby-mesh-windows-radio-v1', 'event': 'fatal',
                          'reason': 'transport_or_setup_failed'}), flush=True)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
