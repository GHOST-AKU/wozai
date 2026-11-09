import Foundation
import XCTest
@testable import WozaiMesh

func boundPair() -> (BLEMeshStream, BLEMeshStream) {
    var ai: InputStream?, bo: OutputStream?, bi: InputStream?, ao: OutputStream?
    Stream.getBoundStreams(withBufferSize: 16, inputStream: &ai, outputStream: &bo)
    Stream.getBoundStreams(withBufferSize: 16, inputStream: &bi, outputStream: &ao)
    return (BLEMeshStream(input: ai!, output: ao!), BLEMeshStream(input: bi!, output: bo!))
}
func readAll(_ stream: any MeshByteStream, count: Int) async throws -> Data {
    var result = Data()
    while result.count < count {
        let data = try await stream.read(maximum: min(4096, count-result.count))
        if data.isEmpty { throw MeshError.closed }
        result.append(data)
    }
    return result
}
final class MeshStreamTests: XCTestCase {
    func testPartialWritesAndBackpressurePreserveAllBytes() async throws {
        let (a,b) = boundPair(); defer { a.close(); b.close() }
        let bytes = Data((0..<32772).map { UInt8($0 % 251) })
        async let received = readAll(b, count: bytes.count)
        try await a.write(bytes)
        let value = try await received
        XCTAssertEqual(value, bytes)
    }
    func testCancelReadClosesAndWakesPeer() async throws {
        let (a,b) = boundPair(); defer { a.close(); b.close() }
        let task = Task { try await a.read(maximum: 8) }
        task.cancel()
        do { _ = try await task.value; XCTFail("canceled read returned successfully") } catch { }
        do { try await a.write(Data([1])); XCTFail("closed stream accepted write") } catch { }
    }
    func testExistingCoCHandshakeKeepsNextCoreFrameIntact() async throws {
        let (a,b) = boundPair(); defer { a.close(); b.close() }
        async let left = MeshCoCProtocol.handshake(a, local: "-123456", expected: "abc", outbound: true)
        async let right = MeshCoCProtocol.handshake(b, local: "abc", expected: nil, outbound: false)
        let result = try await (left,right)
        XCTAssertEqual(result.0,"abc"); XCTAssertEqual(result.1,"-123456")
        try await a.write(Data([0,0,0,3,1,2,3]))
        let next = try await readAll(b, count:7)
        XCTAssertEqual(next, Data([0,0,0,3,1,2,3]))
    }
}
