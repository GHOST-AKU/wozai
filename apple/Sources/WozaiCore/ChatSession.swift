import Foundation

public enum SessionEvent: Sendable {
    case consent(VerifiedPeer)
    case ready(VerifiedPeer)
    case textLimit(String, Int)
    case changed(String)
    case attachmentOffer(AttachmentInfo?)
    case transfer(AttachmentRecord)
    case closed(String?)
}

/// Session deadlines use elapsed time; wall-clock corrections only affect displayed timestamps.
struct SessionDeadlines: Sendable {
    enum Action: Equatable, Sendable { case none, ping, timeout }
    private let started: ContinuousClock.Instant
    private var lastRead: ContinuousClock.Instant

    init(now: ContinuousClock.Instant = ContinuousClock.now) { started = now; lastRead = now }

    mutating func recordRead(at now: ContinuousClock.Instant = ContinuousClock.now) { lastRead = now }

    func action(peerKnown: Bool, ready: Bool, at now: ContinuousClock.Instant = ContinuousClock.now) -> Action {
        let lifetime = started.duration(to: now), idle = lastRead.duration(to: now)
        if (!peerKnown && lifetime > .seconds(15)) || (!ready && lifetime > .seconds(60)) || (ready && idle > .seconds(45)) {
            return .timeout
        }
        return ready && idle > .seconds(10) ? .ping : .none
    }
}

/// Consent gates all content; persistence gates ACK, and a disconnect never resends text.
public actor ChatSession {
    private let attachmentDirectory: URL?
    private var attachments: AttachmentEngine?
    private var pendingOffer: (AttachmentPacket, ContinuousClock.Instant)?
    private var filePump: Task<Void, Never>?
    private var filePumpID: UUID?
    private var fileEpoch: UInt64 = 0
    private var lastTransferPublish: [String: (ContinuousClock.Instant, String)] = [:]
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
    private var deadlines = SessionDeadlines()
    private var isReady: Bool { approved && remoteReady && !ended }

    public init(stream: any ByteStream, identity: DeviceIdentity, nickname: String, initiator: Bool,
                store: ChatStore, attachmentDirectory: URL? = nil, event: @escaping @Sendable (SessionEvent) async -> Void) {
        channel = NIMChannel(stream: stream, identity: identity, nickname: nickname, initiator: initiator)
        self.store = store; self.identity = identity; self.event = event; self.attachmentDirectory = attachmentDirectory
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
            if let directory = attachmentDirectory, let generation = await channel.connectionGeneration {
                attachments = try AttachmentEngine(peer: authenticated, connection: generation,
                    journal: store.directory.appendingPathComponent("attachments-v2", isDirectory: true), directory: directory)
            }
            if pin != nil { try await approve(remember: true) } else { await event(.consent(authenticated)) }
            while !ended {
                let frame = try await channel.receive()
                guard !ended else { return }
                deadlines.recordRead()
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
                    let previous = await channel.textLimit
                    let limit = try await channel.handleTextCapability(frame.timestamp)
                    if limit != previous { await event(.textLimit(authenticated.id, limit)) }
                case .attachment:
                    guard isReady else { throw WozaiError.protocolViolation }
                    if let attachments {
                        let packet = try AttachmentPacket.decode(frame.binary)
                        guard let generation = await channel.connectionGeneration, packet.connection == generation else { throw WozaiError.protocolViolation }
                        if let pendingOffer, pendingOffer.0.id == packet.id, pendingOffer.0.source == packet.source,
                           packet.fromSender && [.pause, .cancel].contains(packet.kind) {
                            self.pendingOffer = nil; await event(.attachmentOffer(nil))
                        } else if let pendingOffer, pendingOffer.0.id == packet.id, pendingOffer.0.source == packet.source,
                                  packet.kind == .status, packet.fromSender {
                            // A resume query may already be queued while consent is pending.
                        } else if packet.kind == .offer {
                            if pendingOffer != nil {
                                var rejection = packet.reply(.cancel); rejection.reason = "busy"
                                try await channel.send(rejection.frame())
                            } else {
                                pendingOffer = (packet, .now); await event(.attachmentOffer(packet.info))
                            }
                        } else {
                            try await sendPackets(attachments.handle(packet)); await publishTransfers(); startFilePump()
                        }
                    } else if let rejection = try AttachmentRejection.reply(to: frame.binary) { try await channel.send(rejection) }
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
        // Consent waiting must not consume the connected heartbeat window.
        if remoteReady { deadlines.recordRead() }
        try await channel.send(NIMFrame(type: .ready))
        try await announceReady()
    }

    private func announceReady() async throws {
        guard isReady, !announced, let peer else { return }
        announced = true
        deadlines.recordRead()
        try await store.recordPeer(peer, remember: remember, localRoot: identity.root)
        guard !ended else { return }
        try await channel.startTextNegotiation()
        await event(.ready(peer))
        await event(.textLimit(peer.id, await channel.textLimit))
    }

    public func sendFile(_ url: URL, mime: String = "application/octet-stream") async throws -> String {
        guard isReady, let attachments else { throw WozaiError.closed }
        let (id, packets) = try attachments.offer(url, mime: mime)
        try await sendPackets(packets); await publishTransfers(); startFilePump()
        return id
    }
    public func acceptFile(_ id: String, accept: Bool) async throws {
        guard isReady, let offer = pendingOffer?.0, offer.id == id, let attachments else { throw WozaiError.closed }
        pendingOffer = nil
        if accept { try await sendPackets(attachments.handle(offer)) }
        else { var rejected = offer.reply(.cancel); rejected.reason = "rejected"; try await sendPackets([rejected]) }
        await event(.attachmentOffer(nil)); await publishTransfers(); startFilePump()
    }
    public func controlFile(_ id: String, cancel: Bool = false, resume: Bool = false) async throws {
        guard isReady, let attachments else { throw WozaiError.closed }
        fileEpoch &+= 1
        try await sendPackets(resume ? attachments.resume(id) : attachments.pause(id, cancel: cancel))
        await publishTransfers(force: true); startFilePump()
    }
    public func transfers() throws -> [AttachmentRecord] { try attachments?.records() ?? [] }
    private func sendPackets(_ packets: [AttachmentPacket]) async throws {
        let epoch = fileEpoch
        for packet in packets {
            guard isReady, fileEpoch == epoch else { return }
            try await channel.send(packet.frame())
        }
    }
    private func publishTransfers(force: Bool = false) async {
        guard let attachments else { return }
        var latest: [String: AttachmentRecord] = [:]
        for record in attachments.drainNotices() { latest[record.id] = record }
        for record in latest.values {
            let now = ContinuousClock.now, previous = lastTransferPublish[record.id]
            if force || previous == nil || previous?.1 != record.state || previous!.0.duration(to: now) >= .milliseconds(100) {
                lastTransferPublish[record.id] = (now, record.state); await event(.transfer(record))
            }
        }
    }
    private func startFilePump() {
        guard isReady, filePump == nil, attachments?.canPump == true else { return }
        let id = UUID(); filePumpID = id
        filePump = Task { [weak self] in
            while await self?.pumpFiles() == true { await Task.yield() }
            await self?.finishFilePump(id)
        }
    }
    private func pumpFiles() async -> Bool {
        guard isReady, let attachments, attachments.canPump else { return false }
        do { let packets = try attachments.pump(); try await sendPackets(packets); await publishTransfers(); return !packets.isEmpty }
        catch { await stop(reason: error.localizedDescription); return false }
    }
    private func finishFilePump(_ id: UUID) { if filePumpID == id { filePump = nil; filePumpID = nil; startFilePump() } }

    public func sendText(_ text: String) async throws {
        guard isReady, let peer else { throw WozaiError.closed }
        guard pending.count < 32 else { throw WozaiError.busy }
        let id = UUID().uuidString.lowercased()
        let frame = NIMFrame(type: .text, id: id, body: text, timestamp: UInt64(Date().timeIntervalSince1970 * 1000))
        _ = try frame.encoded(textLimit: await channel.textLimit)
        pending.insert(id)
        do { try await store.saveOutgoing(peerID: peer.id, frame: frame) }
        catch { pending.remove(id); throw error }
        guard !ended else {
            try? await store.setOutgoingStatus(peerID: peer.id, id: id, status: .unconfirmed)
            throw WozaiError.closed
        }
        await event(.changed(peer.id))
        do {
            try await channel.send(frame)
            guard !ended else { throw WozaiError.closed }
            try await store.setOutgoingStatus(peerID: peer.id, id: id, status: .sent)
            await event(.changed(peer.id))
        } catch {
            await stop(reason: error.localizedDescription)
            throw error
        }
    }

    private func tick() async {
        guard !ended else { return }
        if let pendingOffer, pendingOffer.1.duration(to: .now) > .seconds(50) {
            try? await acceptFile(pendingOffer.0.id, accept: false)
        }
        switch deadlines.action(peerKnown: peer != nil, ready: isReady) {
        case .timeout:
            await stop(reason: WozaiError.timeout.localizedDescription)
        case .ping:
            do { try await channel.send(NIMFrame(type: .ping)) } catch { await stop(reason: error.localizedDescription) }
        case .none: break
        }
    }

    public func stop(reason: String? = nil) async {
        guard !ended else { return }
        ended = true
        timer?.cancel(); timer = nil
        fileEpoch &+= 1; filePump?.cancel(); filePump = nil; filePumpID = nil; pendingOffer = nil
        var closedReason = reason
        do { try attachments?.disconnect() } catch { closedReason = error.localizedDescription }
        await publishTransfers(force: true)
        await channel.close()
        if let peer {
            for id in pending { try? await store.setOutgoingStatus(peerID: peer.id, id: id, status: .unconfirmed) }
            pending.removeAll()
            await event(.changed(peer.id))
        }
        await event(.closed(closedReason))
    }
}
