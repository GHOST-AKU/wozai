import Network
import XCTest
@testable import WozaiCore

final class PeerNetworkTests: XCTestCase {
    func testDiscoveryListenerAndConnectionsAllowSystemPeerInterfaces() {
        let parameters = localParameters()
        XCTAssertTrue(parameters.includePeerToPeer)
        XCTAssertEqual(parameters.prohibitedInterfaceTypes?.contains(.cellular), true)
        XCTAssertNil(parameters.requiredInterface)
    }

    func testManualLANRemainsAvailableWithoutPeerDiscovery() {
        let parameters = localParameters(peerToPeer: false)
        XCTAssertFalse(parameters.includePeerToPeer)
        XCTAssertEqual(parameters.prohibitedInterfaceTypes?.contains(.cellular), true)
    }

    func testBonjourConnectionDoesNotRemainPinnedToDiscoveryInterface() {
        let endpoint = NWEndpoint.service(name: "nearby-test", type: "_nearbyim._tcp", domain: "local.", interface: nil)
        guard case .service(let name, let type, let domain, let interface) = automaticLocalEndpoint(endpoint) else {
            return XCTFail("A service must remain a service, rather than being resolved to a stale IP")
        }
        XCTAssertEqual(name, "nearby-test"); XCTAssertEqual(type, "_nearbyim._tcp")
        XCTAssertEqual(domain, "local."); XCTAssertNil(interface)
    }

    func testPeerEligibilityNeverClaimsAnEstablishedDirectRoute() {
        let snapshot = LocalPathSnapshot.unavailable
        XCTAssertEqual(snapshot.kind, .unavailable)
        XCTAssertFalse(snapshot.directConfirmed)
        XCTAssertTrue(snapshot.diagnostics.contains("apple_peer_to_peer_enabled=true"))
        XCTAssertTrue(snapshot.diagnostics.contains("file_direct_outcome=system_managed"))
    }

    func testAutomaticListenerStillAcceptsManualLANByteStreams() async throws {
        let listener = try NWListener(using: localParameters())
        let ready = expectation(description: "local listener ready")
        listener.stateUpdateHandler = { if case .ready = $0 { ready.fulfill() } }
        listener.newConnectionHandler = { connection in
            let incoming = NWByteStream(connection: connection)
            Task {
                defer { incoming.close() }
                do {
                    try await incoming.start()
                    let bytes = try await incoming.readExactly(4)
                    try await incoming.write(bytes)
                } catch { XCTFail("Accepted local byte stream failed: \(error)") }
            }
        }
        listener.start(queue: DispatchQueue(label: "peer-compatible-loopback"))
        defer { listener.cancel() }
        await fulfillment(of: [ready], timeout: 5)
        let stream = try NWByteStream(host: "127.0.0.1", port: XCTUnwrap(listener.port).rawValue)
        defer { stream.close() }
        let observed = expectation(description: "actual loopback path")
        observed.assertForOverFulfill = false
        stream.observePath { path in
            if path.kind == .loopback {
                XCTAssertFalse(path.directConfirmed)
                XCTAssertFalse(path.peerToPeerEnabled)
                observed.fulfill()
            }
        }
        try await stream.start()
        try await stream.write(Data("ping".utf8))
        let echoed = try await stream.readExactly(4)
        XCTAssertEqual(echoed, Data("ping".utf8))
        await fulfillment(of: [observed], timeout: 5)
    }
}
