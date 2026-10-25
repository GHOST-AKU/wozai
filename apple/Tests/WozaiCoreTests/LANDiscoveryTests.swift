import Foundation
import Network
import XCTest
import WozaiCore

final class LANDiscoveryTests: XCTestCase {
    func testBonjourDiscoveryUsesAdvertisedMetadataAndHidesOwnService() async throws {
        let localID = UUID().uuidString.lowercased()
        let remoteID = UUID().uuidString.lowercased()
        let localService = "nearby-" + String(localID.prefix(8))
        let remoteService = "txt-regression-" + String(remoteID.prefix(8))
        let remoteName = "测试 Mac · TXT nickname"
        let observed = DiscoveryObservation()
        let queue = DispatchQueue(label: "bonjour-regression")
        let remote = try NWListener(using: .tcp)
        remote.service = NWListener.Service(name: remoteService, type: "_nearbyim._tcp", domain: "local.",
            txtRecord: NWTXTRecord(["id": remoteID, "name": remoteName]))
        remote.newConnectionHandler = { $0.cancel() }
        remote.stateUpdateHandler = { if case .failed(let error) = $0 { observed.fail(error.localizedDescription) } }

        // An independent browser verifies that both real advertisements are visible.
        // This distinguishes a production discovery bug from unavailable multicast.
        let witness = NWBrowser(for: .bonjourWithTXTRecord(type: "_nearbyim._tcp", domain: "local."), using: .tcp)
        witness.browseResultsChangedHandler = { results, _ in
            let ids = results.compactMap { result -> String? in
                guard case .bonjour(let txt) = result.metadata else { return nil }
                return txt["id"]
            }
            observed.advertised(ids)
        }
        witness.stateUpdateHandler = { if case .failed(let error) = $0 { observed.fail(error.localizedDescription) } }
        let discovery = LANDiscovery(peers: { observed.discovered($0) }, incoming: { $0.close() },
            state: { _, error in if let error { observed.fail(error) } })
        defer { discovery.stop(); witness.cancel(); remote.cancel() }
        remote.start(queue: queue)
        witness.start(queue: queue)
        discovery.start(id: localID, nickname: "Local discovery regression")

        let deadline = ContinuousClock.now.advanced(by: .seconds(15))
        while true {
            let snapshot = observed.snapshot()
            if let error = snapshot.failure { throw DiscoveryFailure(description: error) }
            let peer = snapshot.peers.first { serviceName($0) == remoteService }
            if snapshot.advertisedIDs.isSuperset(of: [localID, remoteID]), let peer {
                XCTAssertEqual(peer.nickname, remoteName, "Discovery must display the advertised nickname, not the service identifier")
                XCTAssertEqual(peer.claimedID, remoteID, "TXT identity is a discovery hint, not authenticated trust")
                XCTAssertFalse(snapshot.peers.contains { serviceName($0) == localService }, "The client must not offer its own service as a nearby peer")
                XCTAssertFalse(snapshot.offeredServices.contains(localService), "The client's own advertisement must never be offered for connection")
                return
            }
            guard ContinuousClock.now < deadline else {
                throw DiscoveryFailure(description: "Bonjour fixture did not become observable: advertisements=\(snapshot.advertisedIDs), peers=\(snapshot.peers.map(\.nickname))")
            }
            try await Task.sleep(for: .milliseconds(50))
        }
    }
}

private func serviceName(_ peer: DiscoveredPeer) -> String? {
    if case .service(let name, _, _, _) = peer.endpoint { return name }
    return nil
}

private struct DiscoveryFailure: Error, CustomStringConvertible { let description: String }

private final class DiscoveryObservation: @unchecked Sendable {
    struct Snapshot {
        var peers: [DiscoveredPeer] = []
        var advertisedIDs: Set<String> = []
        var offeredServices: Set<String> = []
        var failure: String?
    }
    private let lock = NSLock()
    private var value = Snapshot()

    func discovered(_ peers: [DiscoveredPeer]) {
        lock.withLock {
            value.peers = peers
            value.offeredServices.formUnion(peers.compactMap(serviceName))
        }
    }
    func advertised(_ ids: [String]) { lock.withLock { value.advertisedIDs.formUnion(ids) } }
    func fail(_ error: String) { lock.withLock { value.failure = error } }
    func snapshot() -> Snapshot { lock.withLock { value } }
}
