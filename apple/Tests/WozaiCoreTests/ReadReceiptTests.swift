import XCTest
@testable import WozaiCore

final class ReadReceiptTests: XCTestCase {
    func testBoundedWireAndRevocableReciprocalPolicies() throws {
        var a = ReadReceiptCapability(generation: String(repeating: "a", count: 64), enabled: true)
        var b = ReadReceiptCapability(generation: String(repeating: "a", count: 64), enabled: false)
        let requestA = a.start()!, requestB = b.start()!
        let responseB = b.receiveToken(requestA)!, responseA = a.receiveToken(requestB)!
        XCTAssertNil(a.receiveToken(responseB)); XCTAssertNil(b.receiveToken(responseA))
        _ = try a.receive(b.policy); _ = try b.receive(a.policy)
        XCTAssertFalse(a.active); XCTAssertFalse(b.active)
        b.configure(true); _ = try a.receive(b.policy)
        XCTAssertTrue(a.active); XCTAssertTrue(b.active)
        let id = UUID().uuidString.lowercased(), packet = a.send([id])!
        let data = try packet.encoded()
        XCTAssertEqual(data.count, 35); XCTAssertEqual(try ReadReceiptPacket.decode(data), packet)
        XCTAssertTrue(try b.receive(packet)); XCTAssertFalse(try b.receive(packet), "Duplicate content must not flood persistence")
        let ack = b.acknowledge(packet)!
        XCTAssertTrue(try a.receive(ack)); XCTAssertFalse(try a.receive(ack)); XCTAssertNil(a.send([id]))
        a.persisted(ack); XCTAssertNotNil(a.send([id]))
        b.configure(false); XCTAssertFalse(b.current(packet)); b.configure(true); XCTAssertFalse(b.current(packet))
        XCTAssertThrowsError(try ReadReceiptPacket.decode(data + Data([0])))
        XCTAssertThrowsError(try ReadReceiptPacket(kind: .read, epoch: 1, peerEpoch: 1, enabled: false, ids: [id,id]).encoded())
        let frame = try packet.frame(); XCTAssertEqual(try NIMFrame.decode(frame.encoded()), frame)
        var legacy = ReadReceiptCapability(generation: String(repeating: "b", count: 64), enabled: true)
        _ = legacy.start(); _ = legacy.receiveToken(requestA); XCTAssertFalse(legacy.active)
        XCTAssertThrowsError(try legacy.receive(b.policy))
    }
    func testVisibilityDwellFocusAndOffscreenGeometry() {
        var v = ReadVisibility(); let now = ContinuousClock.now
        XCTAssertTrue(v.update(peer: "a", visible: ["1"], foreground: true, now: now).isEmpty)
        XCTAssertTrue(v.update(peer: "a", visible: ["1"], foreground: true, now: now.advanced(by: .milliseconds(249))).isEmpty)
        XCTAssertEqual(v.update(peer: "a", visible: ["1"], foreground: true, now: now.advanced(by: .milliseconds(250))), ["1"])
        XCTAssertTrue(v.update(peer: "b", visible: ["1"], foreground: true, now: now.advanced(by: .seconds(1))).isEmpty)
        _ = v.update(peer: "b", visible: ["1"], foreground: false, now: now.advanced(by: .seconds(2)))
        XCTAssertTrue(v.update(peer: "b", visible: ["1"], foreground: true, now: now.advanced(by: .seconds(3))).isEmpty)
        XCTAssertFalse(ReadVisibility.visible(top: 99, height: 10, viewportHeight: 100))
        XCTAssertTrue(ReadVisibility.visible(top: 95, height: 10, viewportHeight: 100))
        XCTAssertFalse(ReadVisibility.visible(top: 0, height: 0, viewportHeight: 100))
    }
    func testDurableOutboxReadMonotonicityAndDisableCleanup() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: dir) }
        let url = dir.appendingPathComponent("chat.sqlite"), peer = UUID().uuidString.lowercased(), id = UUID().uuidString.lowercased()
        var store: ChatStore? = try ChatStore(url: url)
        let defaultEnabled = try await store!.readReceiptsEnabled(); XCTAssertTrue(defaultEnabled)
        let frame = NIMFrame(type: .text, id: id, body: "visible", timestamp: 1)
        _ = try await store!.saveIncoming(peerID: peer, frame: frame)
        try await store!.saveOutgoing(peerID: peer, frame: frame)
        try await store!.observed(peerID: peer, ids: [id]); try await store!.read(peerID: peer, ids: [id])
        try await store!.setOutgoingStatus(peerID: peer, id: id, status: .delivered)
        try await store!.setOutgoingStatus(peerID: peer, id: id, status: .sent)
        try await store!.setOutgoingStatus(peerID: peer, id: id, status: .unconfirmed)
        store = nil
        let reopened = try ChatStore(url: url)
        let pending = try await reopened.pendingReads(peerID: peer); XCTAssertEqual(pending, [id])
        let messages = try await reopened.messages(peerID: peer); XCTAssertEqual(messages.first(where: { $0.outgoing })?.status, .read)
        try await reopened.setReadReceiptsEnabled(false)
        try await reopened.observed(peerID: peer, ids: [id]); try await reopened.setReadReceiptsEnabled(true)
        let afterDisable = try await reopened.pendingReads(peerID: peer); XCTAssertTrue(afterDisable.isEmpty)
    }
}
