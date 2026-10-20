import Foundation

public enum SessionEvent: Sendable {
    case consent(VerifiedPeer)
    case ready(VerifiedPeer)
    case changed(String)
    case closed(String?)
}

/// Consent gates all content; persistence gates ACK, and a disconnect never resends text.
public actor ChatSession {
    private let channel: NIMChannel
    private let store: ChatStore
    private let identity: DeviceIdentity
    private let event: @Sendable (SessionEvent) async -> Void
    private var peer: VerifiedPeer?
    private var approved = false
    private var remember = false
    private var remoteReady = false
    private var announced = false
    private var ended = false
    private var pending = Set<String>()
    private var timer: Task<Void, Never>?
    private let started = Date()
    private var lastRead = Date()
    private var isReady: Bool { approved && remoteReady && !ended }

    public init(stream: any ByteStream, identity: DeviceIdentity, nickname: String, initiator: Bool,
                store: ChatStore, event: @escaping @Sendable (SessionEvent) async -> Void) {
        channel = NIMChannel(stream: stream, identity: identity, nickname: nickname, initiator: initiator)
        self.store = store; self.identity = identity; self.event = event
    }

    public func run(expectedID: String? = nil) async {
        timer = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .seconds(5)) } catch { return }
                await self?.tick()
            }
        }
        do {
            let authenticated = try await channel.establish(expectedID: expectedID)
            guard !ended else { return }
            let pin = try await store.trustedRoot(peerID: authenticated.id)
            guard pin == nil || pin == authenticated.root else { throw WozaiError.identityChanged }
            peer = authenticated
            if pin != nil { try await approve(remember: true) } else { await event(.consent(authenticated)) }
            while !ended {
                let frame = try await channel.receive()
                guard !ended else { return }
                lastRead = Date()
                switch frame.type {
                case .ready:
                    guard !remoteReady else { throw WozaiError.protocolViolation }
                    remoteReady = true
                    try await announceReady()
                case .text:
                    guard isReady else { throw WozaiError.protocolViolation }
                    _ = try await store.saveIncoming(peerID: authenticated.id, frame: frame)
                    try await channel.send(NIMFrame(type: .ack, id: frame.id))
                    await event(.changed(authenticated.id))
                case .ack:
                    guard isReady else { throw WozaiError.protocolViolation }
                    if pending.remove(frame.id) != nil {
                        try await store.setOutgoingStatus(peerID: authenticated.id, id: frame.id, status: .delivered)
                        await event(.changed(authenticated.id))
                    }
                case .ping:
                    guard isReady else { throw WozaiError.protocolViolation }
                    try await channel.send(NIMFrame(type: .pong))
                case .pong:
                    guard isReady else { throw WozaiError.protocolViolation }
                case .attachment:
                    guard isReady else { throw WozaiError.protocolViolation }
                    if let rejection = try AttachmentRejection.reply(to: frame.binary) { try await channel.send(rejection) }
                case .bye:
                    await stop()
                }
            }
        } catch { await stop(reason: error.localizedDescription) }
    }

    public func approve(remember: Bool) async throws {
        guard peer != nil, !approved, !ended else { throw WozaiError.closed }
        self.remember = remember
        approved = true
        try await channel.send(NIMFrame(type: .ready))
        try await announceReady()
    }

    private func announceReady() async throws {
        guard isReady, !announced, let peer else { return }
        announced = true
        try await store.recordPeer(peer, remember: remember, localRoot: identity.root)
        guard !ended else { return }
        await event(.ready(peer))
    }

    public func sendText(_ text: String) async throws {
        guard isReady, let peer else { throw WozaiError.closed }
        guard pending.count < 32 else { throw WozaiError.busy }
        let id = UUID().uuidString.lowercased()
        let frame = NIMFrame(type: .text, id: id, body: text, timestamp: UInt64(Date().timeIntervalSince1970 * 1000))
        _ = try frame.encoded()
        try await store.saveOutgoing(peerID: peer.id, frame: frame)
        pending.insert(id)
        await event(.changed(peer.id))
        do {
            try await channel.send(frame)
            try await store.setOutgoingStatus(peerID: peer.id, id: id, status: .sent)
            await event(.changed(peer.id))
        } catch {
            await stop(reason: error.localizedDescription)
            throw error
        }
    }

    private func tick() async {
        guard !ended else { return }
        if (peer == nil && Date().timeIntervalSince(started) > 15) || (!isReady && Date().timeIntervalSince(started) > 60) || (isReady && Date().timeIntervalSince(lastRead) > 45) {
            await stop(reason: WozaiError.timeout.localizedDescription)
        } else if isReady && Date().timeIntervalSince(lastRead) > 10 {
            do { try await channel.send(NIMFrame(type: .ping)) } catch { await stop(reason: error.localizedDescription) }
        }
    }

    public func stop(reason: String? = nil) async {
        guard !ended else { return }
        ended = true
        timer?.cancel(); timer = nil
        await channel.close()
        if let peer {
            for id in pending { try? await store.setOutgoingStatus(peerID: peer.id, id: id, status: .unconfirmed) }
            pending.removeAll()
            await event(.changed(peer.id))
        }
        await event(.closed(reason))
    }
}
