import Foundation
import XCTest
@testable import WozaiMesh

final class EmbeddedCoreTests: XCTestCase {
    func testRealRustCoreStartsRelayOffAndKeepsIdentityAfterShutdown() throws {
        XCTAssertTrue(EmbeddedMeshCore.available, "Run tools/build-apple-mesh.sh before native Mesh tests")
        let dir=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:dir) }
        let first=try EmbeddedMeshCore(directory:dir)
        let ready=try XCTUnwrap(first.nextEvent())
        XCTAssertEqual(ready.event,"ready"); XCTAssertEqual(ready.relay,false)
        XCTAssertNotNil(ready.loopbackPort)
        try first.command(["cmd":"send","text":"Apple 公共消息 🌐","ttl":30,"hops":3])
        var queued=false
        for _ in 0..<20 { if try first.nextEvent()?.event == "queued" { queued=true;break } }
        XCTAssertTrue(queued)
        first.close(); first.close()
        let second=try EmbeddedMeshCore(directory:dir); defer { second.close() }
        XCTAssertEqual(try second.nextEvent()?.key,ready.key)
    }
}
