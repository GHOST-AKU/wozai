import XCTest
@testable import WozaiCore

final class TextCapacityTests: XCTestCase {
    func testLegacyDefaultAndNegotiatedUnicodeBoundary() throws {
        let frame = NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: String(repeating: "🙂", count: 8192))
        XCTAssertThrowsError(try frame.encoded())
        let bytes = try frame.encoded(textLimit: TextLimits.extended)
        XCTAssertThrowsError(try NIMFrame.decode(bytes))
        XCTAssertEqual(try NIMFrame.decode(bytes, textLimit: TextLimits.extended), frame)
        XCTAssertThrowsError(try NIMFrame(type: .text, id: frame.id, body: frame.body + "a").encoded(textLimit: TextLimits.extended))
    }
    func testLongMessagesAndDraftSurviveStorageRestart() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("chat.sqlite")
        let store = try ChatStore(url: url)
        let peer = UUID().uuidString.lowercased(), body = String(repeating: "中", count: 10922) + "ab"
        let draft = String(repeating: "👩🏽‍💻e\u{301}", count: 10000)
        try await store.saveOutgoing(peerID: peer, frame: NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: body))
        try await store.saveDraft(peerID: peer, text: draft)
        let reopened = try ChatStore(url: url)
        let messages = try await reopened.messages(peerID: peer), saved = try await reopened.draft(peerID: peer)
        XCTAssertEqual(messages.first?.body, body); XCTAssertEqual(saved, draft)
        do { try await reopened.saveDraft(peerID: peer, text: String(repeating: "x", count: TextLimits.draft + 1)); XCTFail("Unbounded draft") } catch {}
        let unchanged = try await reopened.draft(peerID: peer); XCTAssertEqual(unchanged, draft)
    }
    func testGenerationBoundEchoAndDuplicateSafety() {
        var a = TextCapability(generation: String(repeating: "a", count: 64))
        var b = TextCapability(generation: String(repeating: "a", count: 64))
        let stale = TextCapability(generation: String(repeating: "b", count: 64))
        XCTAssertEqual(a.request, 0x574f57812e4e8cd3)
        XCTAssertEqual(a.response, 0x575213fb17e249ae)
        _ = a.start()
        XCTAssertNil(a.receive(stale.response))
        XCTAssertEqual(a.outgoing, TextLimits.legacy)
        XCTAssertEqual(b.receive(a.request), a.response)
        XCTAssertEqual(b.incoming, TextLimits.extended)
        XCTAssertEqual(b.outgoing, TextLimits.legacy)
        XCTAssertNil(b.receive(a.request))
        XCTAssertNil(a.receive(b.response))
        XCTAssertEqual(a.outgoing, TextLimits.extended)
        XCTAssertEqual(TextCapability(generation: String(repeating: "a", count: 64)).outgoing, TextLimits.legacy)
    }
}
