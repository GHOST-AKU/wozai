import XCTest
@testable import WozaiCore

final class SessionTests: XCTestCase {
    func testDisconnectBeforeWriteCompletionKeepsMessageUnconfirmed() async throws {
        let fixture = try SessionFixture()
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let running = Task { await fixture.session.run() }
        async let remote = fixture.remote.establish()
        let consent = await fixture.events.next()
        guard case .consent = consent else { return XCTFail("Expected consent") }
        _ = try await remote
        try await fixture.remote.send(NIMFrame(type: .ready))
        try await fixture.session.approve(remember: false)
        guard case .ready = await fixture.events.next() else { return XCTFail("Expected ready") }
        await fixture.outgoing.holdNextWrite()
        let sending = Task { try await fixture.session.sendText("Disconnect during write") }
        await fixture.outgoing.waitForHeldWrite()
        await fixture.session.stop()
        await fixture.outgoing.finishHeldWrite()
        _ = try? await sending.value
        let rows = try await fixture.store.messages(peerID: fixture.remoteIdentity.id)
        XCTAssertEqual(rows.first?.status, .unconfirmed)
        await fixture.remote.close()
        await running.value
    }

    func testLateConsentStartsAFreshHeartbeatWindow() async throws {
        let fixture = try SessionFixture()
        defer { try? FileManager.default.removeItem(at: fixture.directory) }
        let running = Task { await fixture.session.run() }
        async let remote = fixture.remote.establish()
        guard case .consent = await fixture.events.next() else { return XCTFail("Expected consent") }
        _ = try await remote
        try await fixture.remote.send(NIMFrame(type: .ready))
        // Stay inside the 60-second consent window, beyond the 45-second idle limit.
        try await Task.sleep(for: .seconds(47))
        try await fixture.session.approve(remember: false)
        guard case .ready = await fixture.events.next() else { return XCTFail("Expected ready") }
        try await Task.sleep(for: .seconds(5))
        let closed = await fixture.events.hasClosed
        XCTAssertFalse(closed, "Consent time must not consume the connected heartbeat window")
        await fixture.session.stop()
        await fixture.remote.close()
        await running.value
    }
}

private struct SessionFixture {
    let directory: URL
    let store: ChatStore
    let remoteIdentity = DeviceIdentity()
    let outgoing = SessionPipe(), incoming = SessionPipe()
    let events = SessionEvents()
    let session: ChatSession
    let remote: NIMChannel

    init() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
        let events = self.events
        session = ChatSession(stream: SessionStream(input: incoming, output: outgoing), identity: DeviceIdentity(),
                              nickname: "Local", initiator: true, store: store, event: { await events.record($0) })
        remote = NIMChannel(stream: SessionStream(input: outgoing, output: incoming), identity: remoteIdentity,
                            nickname: "Remote", initiator: false)
    }
}

private actor SessionEvents {
    var buffered: [SessionEvent] = []
    var waiter: CheckedContinuation<SessionEvent, Never>?
    var hasClosed = false
    func record(_ event: SessionEvent) {
        if case .changed = event { return }
        if case .closed = event { hasClosed = true }
        if let waiter { self.waiter = nil; waiter.resume(returning: event) }
        else { buffered.append(event) }
    }
    func next() async -> SessionEvent {
        if !buffered.isEmpty { return buffered.removeFirst() }
        return await withCheckedContinuation { waiter = $0 }
    }
}

private final class SessionStream: ByteStream, Sendable {
    let input: SessionPipe, output: SessionPipe
    init(input: SessionPipe, output: SessionPipe) { self.input = input; self.output = output }
    func readExactly(_ count: Int) async throws -> Data { try await input.read(count) }
    func write(_ data: Data) async throws { try await output.write(data) }
    func close() { Task { await input.close(); await output.close() } }
}

private actor SessionPipe {
    var bytes = Data(), closed = false, holdNext = false, holding = false
    var reader: CheckedContinuation<Void, Error>?
    var held: CheckedContinuation<Void, Never>?
    var heldWaiter: CheckedContinuation<Void, Never>?
    func read(_ count: Int) async throws -> Data {
        while bytes.count < count {
            guard !closed else { throw WozaiError.closed }
            try await withCheckedThrowingContinuation { reader = $0 }
        }
        let result = Data(bytes.prefix(count)); bytes.removeFirst(count); return result
    }
    func write(_ data: Data) async throws {
        if holdNext {
            holdNext = false; holding = true
            heldWaiter?.resume(); heldWaiter = nil
            // A successful completion may already be queued when cancellation reaches the socket.
            await withCheckedContinuation { held = $0 }
            return
        }
        guard !closed else { throw WozaiError.closed }
        bytes += data; reader?.resume(); reader = nil
    }
    func holdNextWrite() { holdNext = true }
    func waitForHeldWrite() async {
        if holding { return }
        await withCheckedContinuation { heldWaiter = $0 }
    }
    func finishHeldWrite() { held?.resume(); held = nil }
    func close() { closed = true; reader?.resume(throwing: WozaiError.closed); reader = nil }
}
