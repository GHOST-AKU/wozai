import Foundation
import Network
import WozaiCore

/// Exercise the shipping app model with a real Noise peer that deliberately withholds ACKs.
@main
struct ManualDisconnectProbe {
    static func main() async {
        do {
            let root = URL(fileURLWithPath: CommandLine.arguments[1])
            try await run(database: root.appendingPathComponent("disconnect.sqlite"), reconnect: false)
            try await run(database: root.appendingPathComponent("reconnect.sqlite"), reconnect: true)
        } catch { fputs("Manual disconnect probe failed: \(error)\n", stderr); exit(1) }
    }

    @MainActor private static func run(database: URL, reconnect: Bool) async throws {
        let store = try ChatStore(url: database)
        let model = AppModel(store: store)
        model.identity = DeviceIdentity()
        let remoteIdentity = DeviceIdentity(), peer = ProbePeer()
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { connection in
            let stream = NWByteStream(connection: connection)
            Task {
                let channel = NIMChannel(stream: stream, identity: remoteIdentity, nickname: "Withheld ACK peer", initiator: false)
                do {
                    try await stream.start()
                    _ = try await channel.establish()
                    try await channel.send(NIMFrame(type: .ready))
                    while true {
                        let frame = try await channel.receive()
                        switch frame.type {
                        case .ready: break
                        case .text:
                            // Withhold ACK so disconnect must publish the durable unconfirmed status.
                            await peer.received(frame.body)
                        case .ping: try await channel.send(NIMFrame(type: .pong))
                        case .bye: await channel.close(); return
                        default: throw ProbeFailure(description: "Unexpected peer frame: \(frame.type)")
                        }
                    }
                } catch { await channel.close() }
            }
        }
        listener.start(queue: DispatchQueue(label: "manual-disconnect-peer"))
        defer { listener.cancel(); model.disconnect() }
        try await wait("Listener did not acquire its loopback port") { listener.port != nil }
        func connect() { model.connect(host: "127.0.0.1", port: String(listener.port!.rawValue)) }
        connect()
        try await wait("App did not request consent") { model.connectionState == .awaitingConsent }
        model.approve(remember: false)
        try await wait("App did not publish ready history") {
            model.connectionState == .connected && model.revision[remoteIdentity.id, default: 0] > 0
        }
        let readyRevision = model.revision[remoteIdentity.id, default: 0]
        guard await model.send("Sent without an ACK", peerID: remoteIdentity.id) else {
            throw ProbeFailure(description: "App did not send the fixture text")
        }
        try await wait("Sent status was not saved and published") {
            let rows = try await store.messages(peerID: remoteIdentity.id)
            return rows.count == 1 && rows[0].status == .sent
                && model.revision[remoteIdentity.id, default: 0] > readyRevision
        }
        try await wait("Peer did not receive the unacknowledged text") { await peer.hasText }
        let beforeDisconnect = model.revision[remoteIdentity.id, default: 0]
        model.disconnect()
        if reconnect { connect() }
        try await wait("Stop did not persist the unconfirmed status") {
            let rows = try await store.messages(peerID: remoteIdentity.id)
            return rows.first?.status == .unconfirmed
        }
        try await wait("Persisted unconfirmed status did not invalidate the visible peer revision") {
            model.revision[remoteIdentity.id, default: 0] > beforeDisconnect
        }
        if reconnect {
            try await wait("Old session events interfered with the new consent") { model.connectionState == .awaitingConsent }
            model.approve(remember: false)
            try await wait("Old session events interfered with the new connection") {
                model.connectionState == .connected && model.activePeer?.id == remoteIdentity.id
            }
        } else {
            guard model.connectionState == .idle, model.activePeer == nil, model.consent == nil else {
                throw ProbeFailure(description: "An old session event revived the disconnected app")
            }
        }
        let rows = await model.messages(peerID: remoteIdentity.id)
        guard rows.count == 1, rows[0].status == .unconfirmed else {
            throw ProbeFailure(description: "Refreshed history did not show its durable unconfirmed status")
        }
        print("PASS: manual disconnect refreshes unconfirmed history; immediate reconnect=\(reconnect)")
    }

    @MainActor private static func wait(_ message: String, until condition: () async throws -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while ContinuousClock.now < deadline {
            if try await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        throw ProbeFailure(description: message)
    }
}

private struct ProbeFailure: Error, CustomStringConvertible { let description: String }

private actor ProbePeer {
    var hasText = false
    func received(_ body: String) { hasText = body == "Sent without an ACK" }
}
