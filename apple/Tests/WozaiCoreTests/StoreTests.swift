import XCTest
@testable import WozaiCore

final class StoreTests: XCTestCase {
    func testPersistentReceiptIdempotencyAndDirectionIsolation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("chat.sqlite")
        let peerID = UUID().uuidString.lowercased()
        let id = UUID().uuidString.lowercased()
        let frame = NIMFrame(type: .text, id: id, body: "hello 👩🏽‍💻", timestamp: 1)
        var store: ChatStore? = try ChatStore(url: url)
        let first = try await store!.saveIncoming(peerID: peerID, frame: frame)
        let repeated = try await store!.saveIncoming(peerID: peerID, frame: frame)
        XCTAssertTrue(first)
        XCTAssertFalse(repeated)
        do {
            _ = try await store!.saveIncoming(peerID: peerID, frame: NIMFrame(type: .text, id: id, body: "different", timestamp: 1))
            XCTFail("A reused message UUID changed persisted content")
        } catch {}
        try await store!.saveOutgoing(peerID: peerID, frame: frame)
        try await store!.setOutgoingStatus(peerID: peerID, id: id, status: .delivered)
        let before = try await store!.messages(peerID: peerID)
        XCTAssertEqual(before.count, 2)
        XCTAssertEqual(before.first(where: { $0.outgoing })?.status, .delivered)
        store = nil
        let reopened = try ChatStore(url: url)
        let after = try await reopened.messages(peerID: peerID)
        XCTAssertEqual(after, before)
    }

    func testUnrememberedPeerIsNotTrustedAndRootChangeCannotOverwritePin() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
        let id = UUID().uuidString.lowercased()
        let root = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        let peer = VerifiedPeer(id: id, nickname: "Mac", root: root, capabilities: 3)
        try await store.remember(peer, localRoot: root)
        let known = try await store.trustedRoot(peerID: id)
        XCTAssertEqual(known, root)
        let unknown = try await store.trustedRoot(peerID: UUID().uuidString.lowercased())
        XCTAssertNil(unknown)
        let other = VerifiedPeer(id: id, nickname: "changed", root: DeviceIdentity().root, capabilities: 3)
        do { try await store.remember(other, localRoot: root); XCTFail("Changed identity overwrote trust") } catch {}
        let pinnedAfterRejection = try await store.trustedRoot(peerID: id)
        XCTAssertEqual(pinnedAfterRejection, root)
        try await store.forget(peerID: id)
        let forgotten = try await store.trustedRoot(peerID: id)
        XCTAssertNil(forgotten)
    }
}

extension StoreTests {
    func testPagingPreservesMessagesWithIdenticalTimestamps() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
        let peer = UUID().uuidString.lowercased()
        for index in 0..<205 {
            try await store.saveOutgoing(peerID: peer, frame: NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: "message \(index)", timestamp: 1234))
        }
        let last = try await store.messages(peerID: peer)
        XCTAssertEqual(last.count, 200)
        let earlier = try await store.messages(peerID: peer, before: XCTUnwrap(last.first?.cursor))
        XCTAssertEqual(earlier.count, 5)
        XCTAssertEqual((earlier + last).map(\.body), (0..<205).map { "message \($0)" })
    }

    func testRestartMarksOutstandingMessagesUnconfirmedWithoutRegressingDelivery() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("chat.sqlite")
        let peer = UUID().uuidString.lowercased()
        var store: ChatStore? = try ChatStore(url: url)
        let pending = UUID().uuidString.lowercased(), delivered = UUID().uuidString.lowercased()
        for id in [pending, delivered] { try await store!.saveOutgoing(peerID: peer, frame: NIMFrame(type: .text, id: id, body: id)) }
        try await store!.setOutgoingStatus(peerID: peer, id: delivered, status: .delivered)
        try await store!.setOutgoingStatus(peerID: peer, id: delivered, status: .sent)
        store = nil
        let reopened = try ChatStore(url: url)
        let messages = try await reopened.messages(peerID: peer)
        XCTAssertEqual(messages.first(where: { $0.id == pending })?.status, .unconfirmed)
        XCTAssertEqual(messages.first(where: { $0.id == delivered })?.status, .delivered)
    }

    func testEmbeddedNullAndEmojiSurviveDurableRoundTrip() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
        let peer = UUID().uuidString.lowercased()
        let body = "a\u{0000}b👩🏽‍💻"
        let frame = NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: body, timestamp: 1)
        _ = try await store.saveIncoming(peerID: peer, frame: frame)
        let messages = try await store.messages(peerID: peer)
        XCTAssertEqual(messages.first?.body, body, "SQLite must receive the explicit UTF-8 byte length, not strlen")
    }
}


extension StoreTests {
    func testDatabaseDirectoryIsExcludedFromBackupIncludingOnReopen() async throws {
        var directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("chat.sqlite")
        let store = try ChatStore(url: url)
        try await store.saveDraft(peerID: "backup-test", text: "private draft")
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path + "-wal"))
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = false
        try directory.setResourceValues(values)
        let reopened = try ChatStore(url: url)
        _ = try await reopened.draft(peerID: "backup-test")
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true,
                       "Existing installations must also receive the backup exclusion")
    }
}
