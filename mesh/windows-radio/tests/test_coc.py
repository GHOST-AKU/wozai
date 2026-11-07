"""Real Bumble credit channels over virtual HCI controllers; no RF acceptance claim."""
import asyncio
from contextlib import suppress
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from nearby_coc import Adapter, ChannelStream, LIMIT, SERVICE, parse_offer
from bumble.controller import Controller
from bumble.device import Device, Advertisement
from bumble.hci import Address
from bumble.link import LocalLink
from bumble.transport.common import AsyncPipeSink
from bumble.l2cap import LeCreditBasedChannelSpec


def response(label=b'abcdef12', psm=128):
    field = b'\x21' + SERVICE + bytes([1, len(label)]) + label + psm.to_bytes(2, 'big')
    return bytes([len(field)]) + field


class CodecTests(unittest.TestCase):
    def test_android_independent_vector_and_bad_advertisements(self):
        raw = bytes.fromhex('1d21e0712c82bc948da89c4f835c91c8d3ee010861626364656631320080')
        self.assertEqual(parse_offer(raw), ('abcdef12', 128))
        for data in [b'', raw[:-1], bytes([255])+raw[1:], response(b'bad.name'), response(b'123456789'), response(psm=0), b'\x00'+raw]:
            self.assertIsNone(parse_offer(data), data)
        wrong = bytearray(raw); wrong[2] ^= 1
        self.assertIsNone(parse_offer(wrong))


class VirtualTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.link = LocalLink()
        self.devices = []
        for index in [1, 2, 3]:
            address = f'F0:F1:F2:F3:F4:{index:02X}'
            controller = Controller(f'controller{index}', link=self.link, public_address=address)
            device = Device.with_hci(f'device{index}', Address(address), controller, AsyncPipeSink(controller))
            await device.power_on()
            self.devices.append(device)
        self.client, self.server, self.other = self.devices
        self.peers = []
        self.jobs = []
        self.servers = []

    async def asyncTearDown(self):
        for job in self.jobs:
            job.cancel()
        await asyncio.gather(*self.jobs, return_exceptions=True)
        for server in self.servers:
            server.close(); await server.wait_closed()
        for connection in self.peers:
            with suppress(Exception):
                await asyncio.wait_for(connection.disconnect(), 1)

    async def channel(self, handler):
        server = self.server.create_l2cap_server(LeCreditBasedChannelSpec(psm=128, mtu=2048, mps=2048, max_credits=8), handler)
        await self.server.start_advertising(auto_restart=True)
        connection = await self.client.connect(self.server.random_address, timeout=2)
        self.peers.append(connection)
        return server, await asyncio.wait_for(connection.create_l2cap_channel(LeCreditBasedChannelSpec(psm=128, mtu=2048, mps=2048, max_credits=8)), 3)

    async def test_real_credit_channel_preface_fragmentation_and_duplex_core_bridge(self):
        async def core(reader, writer):
            try:
                data = await reader.readexactly(12000)
                self.assertEqual(data, b'a'*12000)
                writer.write(b'b'*10000); await writer.drain()
            finally:
                writer.close(); await writer.wait_closed()
        tcp = await asyncio.start_server(core, '127.0.0.1', 0)
        self.servers.append(tcp)
        seen = asyncio.get_running_loop().create_future()

        def accepted(channel):
            stream = ChannelStream(channel)
            async def peer():
                self.assertEqual(await stream.exact(6), b'NMLC\x01\x03')
                self.assertEqual(await stream.exact(3), b'-pc')
                # Android-compatible prefix fragmented across separate SDUs.
                for part in [b'N', b'MLC\x01\x08', b'abcdef12', b'R']:
                    await stream.write(part)
                self.assertEqual(await stream.exact(1), b'R')
                for _ in range(6):
                    await stream.write(b'a'*2000)
                result = await stream.exact(10000)
                seen.set_result(result)
            self.jobs.append(asyncio.create_task(peer()))
        _, channel = await self.channel(accepted)
        stream = ChannelStream(channel)
        await asyncio.wait_for(stream.handshake('-pc', 'abcdef12'), 3)
        bridge = asyncio.create_task(stream.bridge(tcp.sockets[0].getsockname()[1]))
        self.jobs.append(bridge)
        self.assertEqual(await asyncio.wait_for(seen, 3), b'b'*10000)
        await asyncio.wait_for(bridge, 3)

    async def test_three_real_core_processes_relay_over_virtual_coc(self):
        sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'tests'))
        from test_lab import Node
        with tempfile.TemporaryDirectory() as directory:
            nodes = []
            adapter = None
            try:
                for name in ['a', 'b', 'c']:
                    nodes.append(await asyncio.to_thread(Node, Path(directory) / name, relay=(name == 'b')))
                a, b, c = nodes
                adapter = Adapter(self.client, '-pc', int(b.address.rsplit(':', 1)[1]), lambda *_: None)
                for device, node, label in [(self.server, a, 'aaaaaaaa'), (self.other, c, 'cccccccc')]:
                    def accepted(channel, node=node, label=label):
                        stream = ChannelStream(channel)
                        async def peer():
                            await stream.write(b'NMLC\x01\x08' + label.encode())
                            self.assertEqual(await stream.exact(9), b'NMLC\x01\x03-pc')
                            await stream.write(b'R')
                            self.assertEqual(await stream.exact(1), b'R')
                            await stream.bridge(int(node.address.rsplit(':', 1)[1]))
                        self.jobs.append(asyncio.create_task(peer()))
                    device.create_l2cap_server(LeCreditBasedChannelSpec(psm=128, mtu=2048, mps=2048, max_credits=8), accepted)
                    await device.start_advertising(auto_restart=True)
                    adapter.observed(Advertisement(address=device.random_address, data_bytes=response(label.encode())))
                await asyncio.to_thread(a.wait, 'neighbor_up')
                await asyncio.to_thread(c.wait, 'neighbor_up')
                a.command('send', text='virtual CoC A-B-C')
                mid = (await asyncio.to_thread(a.wait, 'queued'))['id']
                got = await asyncio.to_thread(c.wait, 'message')
                self.assertEqual(got['id'], mid)
                self.assertEqual(got['path'], [a.key, b.key, c.key])
                c.command('send', text='virtual CoC C-B-A')
                reverse = (await asyncio.to_thread(c.wait, 'queued'))['id']
                self.assertEqual((await asyncio.to_thread(a.wait, 'message'))['id'], reverse)
                b.command('relay', enabled=False)
                await asyncio.to_thread(b.wait, 'relay')
                a.command('send', text='relay-off control', ttl=1)
                blocked = (await asyncio.to_thread(a.wait, 'queued'))['id']
                await asyncio.to_thread(b.wait, 'message', predicate=lambda e: e['id'] == blocked)
                await asyncio.to_thread(c.assert_absent, 'message', timeout=1.2, predicate=lambda e: e['id'] == blocked)
                await asyncio.to_thread(a.wait, 'expired', predicate=lambda e: e['id'] == blocked)
            finally:
                if adapter:
                    await adapter.close()
                for node in reversed(nodes):
                    await asyncio.to_thread(node.close)

    async def test_ingress_overflow_is_bounded_and_fails_closed(self):
        class Channel:
            def on(self, *_): pass
        stream = ChannelStream(Channel())
        stream.receive(b'x'*LIMIT)
        self.assertEqual(len(stream.buffer), LIMIT)
        stream.receive(b'x')
        self.assertTrue(stream.closed)
        self.assertEqual(len(stream.buffer), 0)
        with self.assertRaisesRegex(OSError, 'ingress_bound'):
            await stream.read()

    async def test_write_timeout_and_eof_during_preface(self):
        class Channel:
            def on(self, *_): pass
            def write(self, _): pass
            async def drain(self): await asyncio.Future()
        stream = ChannelStream(Channel())
        with self.assertRaises(TimeoutError):
            await stream.write(b'timeout')
        stream.eof()
        with self.assertRaisesRegex(OSError, 'preface_eof'):
            await stream.exact(1)

    async def test_adapter_admission_limits_and_stop_cleanup(self):
        events = []
        adapter = Adapter(self.client, '-pc', 1, lambda *e: events.append(e))
        # Override only sessions to hold slots; parsing/admission remain production code.
        async def pending(*_): await asyncio.Future()
        adapter.session = pending
        for n in range(50):
            adapter.observed(Advertisement(address=Address('F0:F1:F2:F3:F4:01'), data_bytes=response(f'{n:08x}'.encode())))
        self.assertEqual(len(adapter.tasks), 4)
        self.assertEqual(len(adapter.candidates), 16)
        await adapter.close()
        self.assertTrue(all(task.done() for task in adapter.tasks.values()))
        adapter.observed(Advertisement(address=Address('F0:F1:F2:F3:F4:01'), data_bytes=response()))
        self.assertEqual(len(adapter.candidates), 0)


if __name__ == '__main__':
    unittest.main()
