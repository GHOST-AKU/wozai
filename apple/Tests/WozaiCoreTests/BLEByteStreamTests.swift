import XCTest
@testable import WozaiCore

final class BLEByteStreamTests: XCTestCase {
    func testMinimumMTUTransportsRealNoiseAndLargeRecordsBothWays() async throws {
        let aBox = StreamBox(), bBox = StreamBox()
        let a = BLEByteStream(valueSize: 20, send: { value in
            XCTAssertLessThanOrEqual(value.count, 20)
            guard bBox.stream!.receive(value) else { throw WozaiError.closed }
        }, closed: {})
        let b = BLEByteStream(valueSize: 185, send: { value in
            guard aBox.stream!.receive(value) else { throw WozaiError.closed }
        }, closed: {})
        aBox.stream = a; bBox.stream = b
        let first = NIMChannel(stream: a, identity: DeviceIdentity(), nickname: "Windows role", initiator: true)
        let second = NIMChannel(stream: b, identity: DeviceIdentity(), nickname: "iPhone role", initiator: false)
        async let p = first.establish(); async let q = second.establish()
        _ = try await (p, q)
        for channel in [first, second] {
            let peer = channel === first ? second : first
            let frame = NIMFrame(type: .text, id: UUID().uuidString.lowercased(), body: String(repeating: "密", count: 1000))
            try await channel.send(frame)
            let received = try await peer.receive()
            XCTAssertEqual(received, frame)
        }
        await first.close(); await second.close()
    }

    func testMalformedInputAndResourceOverflowFailClosed() async throws {
        for invalid in [Data(), Data([0]), Data([2, 42]), Data(repeating: 0, count: 513)] {
            let stream = BLEByteStream(valueSize: 20, send: { _ in }, closed: {})
            XCTAssertFalse(stream.receive(invalid))
            do { try await stream.write(Data([42])); XCTFail("Malformed stream accepted write") } catch { }
        }
        let stream = BLEByteStream(valueSize: 20, send: { _ in }, closed: {})
        for _ in 0..<128 { XCTAssertTrue(stream.receive(Data(repeating: 0, count: 512))) }
        XCTAssertFalse(stream.receive(Data(repeating: 0, count: 512)))
        do { _ = try await stream.readExactly(1); XCTFail("Overflow did not close") } catch { }
    }

    func testCancellationAndRemoteCloseWakeReaders() async throws {
        for cancel in [true, false] {
            let stream = BLEByteStream(valueSize: 20, send: { _ in }, closed: {})
            let read = Task { try await stream.readExactly(10) }
            if cancel { read.cancel() } else { XCTAssertTrue(stream.receive(Data([1]))) }
            do { _ = try await read.value; XCTFail("Read survived cancellation/close") } catch { }
            do { try await stream.write(Data([1])); XCTFail("Closed stream accepted write") } catch { }
        }
    }
    func testRemoteCloseDrainsEarlierValuesInOrder() async throws {
        let stream = BLEByteStream(valueSize: 20, send: { _ in }, closed: {})
        XCTAssertTrue(stream.receive(Data([0, 42, 43])))
        XCTAssertTrue(stream.receive(Data([1])))
        let bytes = try await stream.readExactly(2)
        XCTAssertEqual(bytes, Data([42, 43]))
        do { _ = try await stream.readExactly(1); XCTFail("EOF lost") } catch { }
    }
}

private final class StreamBox: @unchecked Sendable { var stream: BLEByteStream? }
