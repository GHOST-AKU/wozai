import Foundation
import Network
import Observation
import WozaiCore

@MainActor @Observable
final class AppModel {
    enum ConnectionState: Equatable { case idle, connecting, awaitingConsent, connected }
    struct Notice: Identifiable { let id = UUID(); let message: String }

    var initialized = false
    var startupError: String?
    var identity: DeviceIdentity?
    var nickname = UserDefaults.standard.string(forKey: "nickname") ?? "NearbyIM"
    var conversations: [Conversation] = []
    var nearby: [DiscoveredPeer] = []
    var addresses: [String] = []
    var discoveryEnabled = false
    var networkError: String?
    var connectionState = ConnectionState.idle
    var activePeer: VerifiedPeer?
    var consent: VerifiedPeer?
    var notice: Notice?
    var revision: [String: UInt64] = [:]
    var newChatRequest = 0
    var busy: Bool { connectionState != .idle }

    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var store: ChatStore?
    @ObservationIgnored private var discovery: LANDiscovery?
    @ObservationIgnored private var session: ChatSession?
    @ObservationIgnored private var stream: NWByteStream?
    @ObservationIgnored private var connectionTask: Task<Void, Never>?
    @ObservationIgnored private var token = UUID()
    @ObservationIgnored private var draftWrites: [String: Task<Void, Never>] = [:]
    @ObservationIgnored private var drafts: [String: String] = [:]
    @ObservationIgnored private var refreshTask: Task<Void, Never>?
    @ObservationIgnored private var changedPeers = Set<String>()

    func bootstrap() async {
        guard !initialized else { return }
        do {
            let identity = try IdentityKeychain.loadOrCreate()
            let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("NearbyIM", isDirectory: true)
            store = try ChatStore(url: directory.appendingPathComponent("chat.sqlite"))
            self.identity = identity
            discovery = LANDiscovery(peers: { [weak self] peers in
                Task { @MainActor in self?.nearby = peers }
            }, incoming: { [weak self] incoming in
                Task { @MainActor in
                    guard let self, self.discoveryEnabled, self.foreground, !self.busy else { incoming.close(); return }
                    self.open(incoming, expectedID: nil, initiator: false)
                }
            }, state: { [weak self] addresses, error in
                Task { @MainActor in self?.addresses = addresses; self?.networkError = error }
            })
            initialized = true
            startupError = nil
            await refreshConversations()
        } catch { startupError = error.localizedDescription }
    }

    func setDiscovery(_ enabled: Bool) {
        guard let identity, let discovery else { return }
        discoveryEnabled = enabled
        networkError = nil
        if enabled && foreground { discovery.start(id: identity.id, nickname: nickname) }
        else { discovery.stop(); nearby = []; addresses = [] }
    }

    func reconnectDiscovery() { if discoveryEnabled { setDiscovery(true) } }

    func connect(_ peer: DiscoveredPeer) { open(NWByteStream(endpoint: peer.endpoint), expectedID: peer.claimedID, initiator: true) }

    func connect(host: String, port: String) {
        do {
            guard let number = UInt16(port), number > 0 else { throw WozaiError.protocolViolation }
            let host = host.trimmingCharacters(in: .whitespacesAndNewlines)
            open(try NWByteStream(host: host, port: number), expectedID: nil, initiator: true)
        } catch { show(error) }
    }

    private func open(_ stream: NWByteStream, expectedID: String?, initiator: Bool) {
        guard foreground, !busy, let identity, let store else { stream.close(); return }
        token = UUID()
        let generation = token
        self.stream = stream
        connectionState = .connecting
        connectionTask = Task {
            do {
                try await stream.start()
                guard token == generation else { stream.close(); return }
                let session = ChatSession(stream: stream, identity: identity, nickname: nickname, initiator: initiator,
                    store: store, event: { [weak self] event in await self?.handle(event, generation: generation) })
                self.session = session
                await session.run(expectedID: expectedID)
            } catch {
                guard token == generation else { return }
                handle(.closed(error.localizedDescription), generation: generation)
            }
        }
    }

    private func handle(_ event: SessionEvent, generation: UUID) {
        guard token == generation else { return }
        switch event {
        case .consent(let peer): activePeer = peer; consent = peer; connectionState = .awaitingConsent
        case .ready(let peer):
            activePeer = peer; consent = nil; connectionState = .connected
            changed(peer.id)
        case .changed(let peer): changed(peer)
        case .closed(let error):
            session = nil; stream = nil; activePeer = nil; consent = nil; connectionState = .idle
            if let error { notice = Notice(message: error) }
        }
    }

    func approve(remember: Bool) {
        guard connectionState == .awaitingConsent, let session else { return }
        connectionState = .connecting
        consent = nil
        let generation = token
        Task {
            do { try await session.approve(remember: remember) }
            catch { guard token == generation else { return }; await session.stop(reason: error.localizedDescription) }
        }
    }

    func dismissConsent() { if connectionState == .awaitingConsent { disconnect() } }

    func disconnect() {
        token = UUID()
        let previous = session
        connectionTask?.cancel(); connectionTask = nil
        stream?.close(); stream = nil; session = nil
        consent = nil; activePeer = nil; connectionState = .idle
        Task { await previous?.stop() }
    }

    func resume() { foreground = true; reconnectDiscovery() }

    func suspend() async {
        foreground = false
        discovery?.stop()
        disconnect()
        for (id, draft) in drafts { try? await store?.saveDraft(peerID: id, text: draft) }
    }

    func messages(peerID: String, before: MessageCursor? = nil) async -> [ChatMessage] {
        do { return try await store?.messages(peerID: peerID, before: before) ?? [] }
        catch is CancellationError { return [] }
        catch { show(error); return [] }
    }

    func loadDraft(peerID: String) async -> String {
        if let cached = drafts[peerID] { return cached }
        let draft = (try? await store?.draft(peerID: peerID)) ?? ""
        drafts[peerID] = draft
        return draft
    }

    func saveDraft(peerID: String, text: String) {
        drafts[peerID] = text
        draftWrites[peerID]?.cancel()
        draftWrites[peerID] = Task {
            do {
                try await Task.sleep(for: .milliseconds(400))
                try Task.checkCancellation()
                try await store?.saveDraft(peerID: peerID, text: text)
                draftWrites[peerID] = nil
            } catch is CancellationError {} catch { show(error) }
        }
    }

    func send(_ text: String, peerID: String) async -> Bool {
        guard connectionState == .connected, activePeer?.id == peerID, let session else { return false }
        do { try await session.sendText(text); return true } catch { show(error); return false }
    }

    func updateNickname(_ name: String) {
        guard !busy, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.unicodeScalars.count <= 32, name.utf8.count <= 128,
              !name.unicodeScalars.contains(where: { $0.value <= 31 || (127...159).contains($0.value) }) else {
            notice = Notice(message: String(localized: "Use a device name of at most 32 Unicode code points and 128 UTF-8 bytes.")); return
        }
        nickname = name
        UserDefaults.standard.set(name, forKey: "nickname")
        reconnectDiscovery()
    }

    func verify(_ peer: VerifiedPeer, generation: UUID) async {
        guard token == generation, activePeer == peer, connectionState == .connected, let identity else { return }
        do { try await store?.markVerified(peer, localRoot: identity.root); await refreshConversations() }
        catch { show(error) }
    }

    var verificationGeneration: UUID { token }

    func forget(_ contact: Conversation) async {
        if activePeer?.id == contact.id { disconnect() }
        do { try await store?.forget(peerID: contact.id); await refreshConversations() } catch { show(error) }
    }

    private func refreshConversations() async {
        guard let identity, let store else { return }
        do { conversations = try await store.conversations(localRoot: identity.root) } catch { show(error) }
    }

    private func changed(_ id: String) {
        changedPeers.insert(id)
        guard refreshTask == nil else { return }
        refreshTask = Task {
            try? await Task.sleep(for: .milliseconds(100))
            let ids = changedPeers
            changedPeers.removeAll()
            for id in ids { revision[id, default: 0] &+= 1 }
            await refreshConversations()
            refreshTask = nil
            if let next = changedPeers.first { changed(next) }
        }
    }

    func show(_ error: Error) { notice = Notice(message: error.localizedDescription) }
}
