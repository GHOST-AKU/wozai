import Foundation
import XCTest
@testable import WozaiMesh

final class EmbeddedCoreTests: XCTestCase {
    private func wait(_ core:EmbeddedMeshCore,_ kind:String) async throws -> PublicMeshEvent {
        for _ in 0..<40 { if let event=try await core.nextEventAsync(),event.event==kind { return event } }
        throw MeshError.timeout
    }
    func testProductionCoCStreamBridgeExchangesRealSignedCoreMessages() async throws {
        let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:root) }
        let a=try EmbeddedMeshCore(directory:root.appendingPathComponent("a"))
        let b=try EmbeddedMeshCore(directory:root.appendingPathComponent("b"))
        defer { a.close();b.close() }
        let ar=try await wait(a,"ready"),br=try await wait(b,"ready")
        let (left,right)=boundPair()
        let bridgeA=Task { try await MeshBridge.run(radio:left,corePort:ar.loopbackPort!,label:"-123456",expected:"abc",outbound:true) }
        let bridgeB=Task { try await MeshBridge.run(radio:right,corePort:br.loopbackPort!,label:"abc",expected:nil,outbound:false) }
        defer { bridgeA.cancel();bridgeB.cancel();left.close();right.close() }
        _ = try await wait(a,"neighbor_up"); _ = try await wait(b,"neighbor_up")
        for (sender,receiver) in [(a,b),(b,a)] {
            try sender.command(["cmd":"send","text":"真实 Core 🌐","ttl":30,"hops":3])
            let queued=try await wait(sender,"queued"),received=try await wait(receiver,"message")
            XCTAssertEqual(queued.id,received.id);XCTAssertEqual(received.body,"真实 Core 🌐")
            XCTAssertEqual(received.path?.count,2)
        }
    }
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
