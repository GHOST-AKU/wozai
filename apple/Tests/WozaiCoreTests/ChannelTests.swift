import XCTest
@testable import WozaiCore

final class ChannelTests: XCTestCase {
    func testConcurrentSendsPreserveEncryptedRecordOrderAcrossSuspension() async throws {
        let outgoing = TestPipe(delayFirstRecord: true), incoming = TestPipe()
        let a = NIMChannel(stream: TestStream(input: incoming, output: outgoing), identity: DeviceIdentity(), nickname: "A", initiator: true)
        let b = NIMChannel(stream: TestStream(input: outgoing, output: incoming), identity: DeviceIdentity(), nickname: "B", initiator: false)
        async let peerA = a.establish()
        async let peerB = b.establish()
        _ = try await (peerA, peerB)
        let first = NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: "first")
        let second = NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: "second")
        let sendFirst = Task { try await a.send(first) }
        await outgoing.waitForFirstRecord()
        let sendSecond = Task { try await a.send(second) }
        try await sendFirst.value
        try await sendSecond.value
        let receivedFirst = try await b.receive(), receivedSecond = try await b.receive()
        XCTAssertEqual(receivedFirst, first)
        XCTAssertEqual(receivedSecond, second)
        await a.close(); await b.close()
    }
}

private final class TestStream: ByteStream, Sendable {
    let input: TestPipe, output: TestPipe
    init(input: TestPipe, output: TestPipe) { self.input = input; self.output = output }
    func readExactly(_ count: Int) async throws -> Data { try await input.read(count) }
    func write(_ data: Data) async throws { try await output.write(data) }
    func close() { Task { await input.close(); await output.close() } }
}

private actor TestPipe {
    var data = Data()
    var closed = false
    var reader: CheckedContinuation<Void, Error>?
    let delayFirstRecord: Bool
    var firstRecordStarted = false
    var firstRecordWaiter: CheckedContinuation<Void, Never>?
    init(delayFirstRecord: Bool = false) { self.delayFirstRecord = delayFirstRecord }

    func read(_ count: Int) async throws -> Data {
        while data.count < count {
            guard !closed else { throw WozaiError.closed }
            try await withCheckedThrowingContinuation { reader = $0 }
        }
        let result = Data(data.prefix(count)); data.removeFirst(count)
        return result
    }

    func write(_ bytes: Data) async throws {
        if delayFirstRecord, !firstRecordStarted, bytes.count >= 10, bytes[9] == 3 {
            firstRecordStarted = true
            firstRecordWaiter?.resume(); firstRecordWaiter = nil
            // A suspended first write must not let a later nonce reach the peer first.
            try await Task.sleep(for: .milliseconds(200))
        }
        guard !closed else { throw WozaiError.closed }
        data += bytes
        reader?.resume(); reader = nil
    }

    func waitForFirstRecord() async {
        if firstRecordStarted { return }
        await withCheckedContinuation { firstRecordWaiter = $0 }
    }

    func close() { closed = true; reader?.resume(throwing: WozaiError.closed); reader = nil }
}
