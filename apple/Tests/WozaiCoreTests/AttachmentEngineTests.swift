import CryptoKit
import Foundation
import XCTest
@testable import WozaiCore

final class AttachmentEngineTests: XCTestCase {
    func testStreamingEmptyAndMultipleBlocksProduceOneDurableReceipt() throws {
        for size in [0, 19, 1024 * 1024 + 17] {
            let pair = try Pair(size: size)
            let (id, packets) = try pair.sender.offer(pair.source, mime: "image/png")
            pair.toReceiver = packets
            try pair.finish(id)
            let record = try XCTUnwrap(pair.receiver.records().first { $0.id == id })
            XCTAssertEqual(record.state, "received")
            XCTAssertEqual(record.info.mime, "image/png")
            XCTAssertEqual(try Data(contentsOf: record.destination), pair.bytes)
            XCTAssertEqual(record.digest, Data(SHA256.hash(data: pair.bytes)))
            XCTAssertEqual(pair.receipts, 1)
        }
    }

    func testReconnectUsesForcedCheckpointAndRejectsChangedRoot() throws {
        let pair = try Pair(size: 2 * 1024 * 1024 + 17)
        let (id, packets) = try pair.sender.offer(pair.source)
        pair.toReceiver = packets
        for _ in 0..<100 {
            try pair.tick()
            if try pair.receiver.records().contains(where: { $0.offset >= 1024 * 1024 }) { break }
        }
        try pair.sender.disconnect(); try pair.receiver.disconnect()
        pair.toReceiver = []; pair.toSender = []
        pair.sender = try pair.engine(outgoing: true, generation: "b")
        pair.receiver = try pair.engine(outgoing: false, generation: "b")
        pair.toReceiver = try pair.sender.resume(id)
        try pair.finish(id)
        let changed = VerifiedPeer(id: pair.remote.id, nickname: "changed", root: DeviceIdentity().root, capabilities: 3)
        let wrong = try AttachmentEngine(peer: changed, connection: String(repeating: "b", count: 64),
            journal: pair.root.appendingPathComponent("sender-journal"), directory: pair.root.appendingPathComponent("unused"))
        XCTAssertThrowsError(try wrong.resume(id))
    }

    func testCancelFencesLateDataAndReleasesSlot() throws {
        let pair = try Pair(size: 32768 * 3)
        let (id, offer) = try pair.sender.offer(pair.source)
        pair.toReceiver = offer
        try pair.tick()
        let queued = pair.toReceiver
        _ = try pair.receiver.pause(id, cancel: true)
        for packet in queued { _ = try pair.receiver.handle(packet) }
        XCTAssertEqual(try pair.receiver.records().first { $0.id == id }?.state, "canceled")
        _ = try pair.sender.pause(id, cancel: true)
        XCTAssertNoThrow(try pair.sender.offer(pair.source))
    }

    func testPersistentQueueDoesNotReplaceCurrentFileAndCanCancelWaitingItems() throws {
        let pair = try Pair(size: 32768 * 3 + 1)
        let (first, packets) = try pair.sender.offer(pair.source)
        pair.toReceiver = packets
        let (second, waiting) = try pair.sender.offer(pair.source)
        XCTAssertTrue(waiting.isEmpty)
        let (canceled, _) = try pair.sender.offer(pair.source)
        XCTAssertTrue(try pair.sender.pause(canceled, cancel: true).isEmpty)
        try pair.sender.disconnect()
        pair.toReceiver = []; pair.toSender = []
        pair.sender = try pair.engine(outgoing: true)
        pair.toReceiver = try pair.sender.resume(first)
        // The receiver never saw OFFER, so restore its initial authorized task first.
        let initial = try XCTUnwrap(packets.first)
        _ = try pair.receiver.handle(initial)
        pair.toReceiver = try pair.sender.resume(first)
        try pair.finish(first); try pair.finish(second)
        let records = try pair.sender.records()
        XCTAssertEqual(records.first { $0.id == canceled }?.state, "canceled")
        XCTAssertEqual(records.filter { $0.state == "saved" }.count, 2)
    }

    private final class Pair {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        let remote = VerifiedPeer(id: UUID().uuidString.lowercased(), nickname: "Peer", root: DeviceIdentity().root, capabilities: 3)
        let local = VerifiedPeer(id: UUID().uuidString.lowercased(), nickname: "Sender", root: DeviceIdentity().root, capabilities: 3)
        let bytes: Data
        var source: URL { root.appendingPathComponent("source.bin") }
        var sender: AttachmentEngine!
        var receiver: AttachmentEngine!
        var toReceiver: [AttachmentPacket] = [], toSender: [AttachmentPacket] = []
        var receipts = 0
        init(size: Int) throws {
            bytes = Data((0..<size).map { UInt8(truncatingIfNeeded: $0 * 37) })
            try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
            try bytes.write(to: source)
            sender = try engine(outgoing: true); receiver = try engine(outgoing: false)
        }
        deinit { try? sender?.disconnect(); try? receiver?.disconnect(); try? FileManager.default.removeItem(at: root) }
        func engine(outgoing: Bool, generation: String = "a") throws -> AttachmentEngine {
            try AttachmentEngine(peer: outgoing ? remote : local, connection: String(repeating: generation, count: 64),
                journal: root.appendingPathComponent(outgoing ? "sender-journal" : "receiver-journal"),
                directory: root.appendingPathComponent(outgoing ? "unused" : "received"))
        }
        func tick() throws {
            let incoming = toReceiver; toReceiver = []
            for packet in incoming {
                let responses = try receiver.handle(AttachmentPacket.decode(packet.encoded()))
                receipts += responses.filter { $0.kind == .complete }.count
                toSender += responses
            }
            let outgoing = toSender; toSender = []
            for packet in outgoing { toReceiver += try sender.handle(AttachmentPacket.decode(packet.encoded())) }
            toReceiver += try sender.pump()
        }
        func finish(_ id: String) throws {
            for _ in 0..<500 {
                try tick()
                if try sender.records().contains(where: { $0.id == id && $0.state == "saved" }) { return }
            }
            XCTFail("Streaming transfer did not reach a durable saved receipt")
        }
    }
}
