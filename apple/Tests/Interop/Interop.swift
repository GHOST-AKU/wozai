import Foundation
import Network
import WozaiCore

@main
struct Interop {
    static func main() async throws {
        let arguments = CommandLine.arguments
        let initiator = arguments[1] == "client"
        let stream: NWByteStream
        let listener: NWListener?
        if initiator {
            stream = try NWByteStream(host: "127.0.0.1", port: UInt16(arguments[2])!)
            listener = nil
        } else {
            let tcp = NWProtocolTCP.Options(); tcp.noDelay = true
            let parameters = NWParameters(tls: nil, tcp: tcp)
            parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
            let server = try NWListener(using: parameters)
            listener = server
            stream = try await withCheckedThrowingContinuation { continuation in
                let gate = AcceptGate(continuation)
                server.newConnectionHandler = { gate.finish(.success(NWByteStream(connection: $0))) }
                server.stateUpdateHandler = { state in
                    do {
                        if case .ready = state, let port = server.port {
                            try String(port.rawValue).write(toFile: arguments[2], atomically: true, encoding: .utf8)
                        } else if case .failed(let error) = state { gate.finish(.failure(error)) }
                    } catch { gate.finish(.failure(error)) }
                }
                let queue = DispatchQueue(label: "interop-listener")
                server.start(queue: queue)
                queue.asyncAfter(deadline: .now() + 25) { gate.finish(.failure(WozaiError.timeout)) }
            }
        }
        defer { listener?.cancel(); stream.close() }
        try await stream.start()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
        let identity = DeviceIdentity()
        let harness = Harness(store: store, identity: identity)
        let session = ChatSession(stream: stream, identity: identity, nickname: "Apple interop peer", initiator: initiator, store: store) { event in
            await harness.handle(event)
        }
        await harness.attach(session)
        let timeout = Task {
            do { try await Task.sleep(for: .seconds(25)); await session.stop(reason: "Interop deadline exceeded") } catch {}
        }
        await session.run()
        timeout.cancel()
        try await harness.verify()
        print("PASS: Swift ChatSession durable text/ACK, production Java interop, Unicode whitespace nickname/text, safety code")
    }
}

private actor Harness {
    let store: ChatStore
    let identity: DeviceIdentity
    var session: ChatSession?
    var peer: VerifiedPeer?
    var closedNormally = false
    var failure: String?
    init(store: ChatStore, identity: DeviceIdentity) { self.store = store; self.identity = identity }
    func attach(_ session: ChatSession) { self.session = session }
    func handle(_ event: SessionEvent) async {
        do {
            switch event {
            case .consent: try await session?.approve(remember: false)
            case .ready(let peer):
                self.peer = peer
                let code = try SafetyCode.make(identity.root, peer.root)
                try await session?.sendText("Hello from Apple\n" + code)
            case .changed, .attachmentOffer, .transfer: break
            case .closed(let reason): closedNormally = reason == nil; failure = reason
            }
        } catch { failure = error.localizedDescription; await session?.stop(reason: failure) }
    }
    func verify() async throws {
        guard closedNormally, failure == nil, let peer else { throw NSError(domain: "Interop", code: 1, userInfo: [NSLocalizedDescriptionKey: failure ?? "Peer did not close normally"]) }
        let messages = try await store.messages(peerID: peer.id)
        let code = try SafetyCode.make(identity.root, peer.root)
        guard messages.count == 3, peer.nickname == "\u{00a0}",
              messages.contains(where: { !$0.outgoing && $0.body == "Hello from Java\n" + code }),
              messages.contains(where: { !$0.outgoing && $0.body == "\u{00a0}\u{2003}" }),
              messages.contains(where: { $0.outgoing && $0.status == .delivered && $0.body == "Hello from Apple\n" + code }) else { throw WozaiError.protocolViolation }
    }
}

private final class AcceptGate: @unchecked Sendable {
    let lock = NSLock()
    var continuation: CheckedContinuation<NWByteStream, Error>?
    init(_ continuation: CheckedContinuation<NWByteStream, Error>) { self.continuation = continuation }
    func finish(_ result: Result<NWByteStream, Error>) {
        lock.lock(); let pending = continuation; continuation = nil; lock.unlock()
        if let pending { pending.resume(with: result) }
        else if case .success(let stream) = result { stream.close() }
    }
}
