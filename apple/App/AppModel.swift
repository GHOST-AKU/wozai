import Foundation
import Network
import Observation
import UniformTypeIdentifiers
import WozaiCore

@MainActor @Observable
final class ConversationDraft {
    var text = ""
    var loaded = false
}

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
    private var fileConsent = AttachmentConsent()
    var fileOffer: AttachmentInfo? { fileConsent.pending }
    var fileOfferToken: UUID { fileConsent.token }
    var transfers: [String: AttachmentRecord] = [:]
    var transferRevision: [String: UInt64] = [:]
    var revision: [String: UInt64] = [:]
    var receiveFolderName = String(localized: "Documents / Received files")
    @ObservationIgnored private var fileLeases = Set<URL>()
    var busy: Bool { connectionState != .idle }

    @ObservationIgnored private var foreground = true
    @ObservationIgnored private var store: ChatStore?
    @ObservationIgnored private var discovery: LANDiscovery?
    @ObservationIgnored private var session: ChatSession?
    @ObservationIgnored private var stream: NWByteStream?
    @ObservationIgnored private var connectionTask: Task<Void, Never>?
    @ObservationIgnored private var token = UUID()
    private struct DraftWrite { let id: UUID; let task: Task<Void, Never> }
    @ObservationIgnored private var draftWrites: [String: DraftWrite] = [:]
    @ObservationIgnored private var drafts: [String: ConversationDraft] = [:]
    @ObservationIgnored private var refreshTask: Task<Void, Never>?
    @ObservationIgnored private var changedPeers = Set<String>()

    init(store: ChatStore? = nil) { self.store = store }

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
                let directory = try receiveFolder()
                let session = ChatSession(stream: stream, identity: identity, nickname: nickname, initiator: initiator,
                    store: store, attachmentDirectory: directory, event: { [weak self] event in await self?.handle(event, generation: generation) })
                self.session = session
                await session.run(expectedID: expectedID)
            } catch {
                guard token == generation else { return }
                handle(.closed(error.localizedDescription), generation: generation)
            }
        }
    }

    private func handle(_ event: SessionEvent, generation: UUID) {
        guard token == generation else {
            // Durable history changes survive session retirement; connection events stay fenced.
            if case .changed(let peer) = event { changed(peer) }
            if case .transfer(let record) = event { transfers[record.key] = record }
            return
        }
        switch event {
        case .consent(let peer): activePeer = peer; consent = peer; connectionState = .awaitingConsent
        case .ready(let peer):
            activePeer = peer; consent = nil; connectionState = .connected
            changed(peer.id)
        case .changed(let peer): changed(peer)
        case .attachmentOffer(let info): fileConsent.replace(info)
        case .transfer(let record):
            if transfers[record.key]?.state != record.state { transferRevision[record.peerID, default: 0] &+= 1 }
            transfers[record.key] = record
        case .closed(let error):
            session = nil; stream = nil; activePeer = nil; consent = nil; fileConsent.replace(nil); connectionState = .idle
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

    func fileError(_ error: Error) { show(error) }
    private func bookmark(_ url: URL) throws -> Data {
        #if os(macOS)
        return try url.bookmarkData(options: .withSecurityScope, includingResourceValuesForKeys: nil, relativeTo: nil)
        #else
        return try url.bookmarkData(options: .minimalBookmark, includingResourceValuesForKeys: nil, relativeTo: nil)
        #endif
    }
    private func resolve(_ data: Data) throws -> URL {
        var stale = false
        #if os(macOS)
        let options: URL.BookmarkResolutionOptions = [.withSecurityScope, .withoutUI]
        #else
        let options: URL.BookmarkResolutionOptions = [.withoutUI]
        #endif
        return try URL(resolvingBookmarkData: data, options: options, relativeTo: nil, bookmarkDataIsStale: &stale)
    }
    func accessFile(_ url: URL) {
        guard !fileLeases.contains(url) else { return }
        let key = "source-bookmark:" + url.absoluteString
        do {
            let resolved = try UserDefaults.standard.data(forKey: key).map(resolve) ?? url
            if resolved.startAccessingSecurityScopedResource() {
                fileLeases.insert(resolved)
                UserDefaults.standard.set(try bookmark(resolved), forKey: key)
            }
        } catch { show(error) }
    }
    private func receiveFolder() throws -> URL {
        let defaults = UserDefaults.standard
        let url = try defaults.data(forKey: "receive-folder").map(resolve)
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Received files", isDirectory: true)
        if !fileLeases.contains(url), url.startAccessingSecurityScopedResource() { fileLeases.insert(url) }
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        receiveFolderName = url.lastPathComponent
        return url
    }
    func chooseReceiveFolder(_ url: URL) {
        guard !busy else { return }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        do { UserDefaults.standard.set(try bookmark(url), forKey: "receive-folder"); receiveFolderName = url.lastPathComponent }
        catch { show(error) }
    }
    func sendFiles(_ urls: [URL]) {
        guard connectionState == .connected, let session, urls.count <= 256 else { return }
        Task {
            do {
                for url in urls {
                    accessFile(url)
                    let type = try url.resourceValues(forKeys: [.contentTypeKey]).contentType
                    _ = try await session.sendFile(url, mime: type?.preferredMIMEType ?? "application/octet-stream")
                }
            } catch { show(error) }
        }
    }
    func decideFile(_ accept: Bool, expected: UUID) {
        guard let session, let offer = fileConsent.take(expected: expected) else { return }
        Task { do { try await session.acceptFile(offer.id, accept: accept) } catch { show(error) } }
    }
    func fileControl(_ id: String, cancel: Bool = false, resume: Bool = false) {
        guard let session else { return }
        Task {
            do {
                if resume, let record = transfers.values.first(where: { $0.id == id && $0.outgoing }) { accessFile(record.path) }
                try await session.controlFile(id, cancel: cancel, resume: resume)
            } catch { show(error) }
        }
    }
    func loadTransfers(_ contact: Conversation) async {
        guard let store else { return }
        let journal = store.directory.appendingPathComponent("attachments-v2", isDirectory: true)
        let peer = VerifiedPeer(id: contact.id, nickname: contact.nickname, root: contact.root, capabilities: 3)
        do {
            let records = try await Task.detached {
                try AttachmentEngine(peer: peer, connection: String(repeating: "0", count: 64), journal: journal, directory: journal).records()
            }.value
            for record in records where transfers[record.key] == nil { transfers[record.key] = record }
        } catch { show(error) }
    }

    func dismissConsent() { if connectionState == .awaitingConsent { disconnect() } }

    func disconnect() {
        token = UUID()
        let previous = session
        connectionTask?.cancel(); connectionTask = nil
        stream?.close(); stream = nil; session = nil
        consent = nil; activePeer = nil; fileConsent.replace(nil); connectionState = .idle
        Task { await previous?.stop() }
    }

    func resume() { foreground = true; reconnectDiscovery() }

    func suspend() async {
        foreground = false
        discovery?.stop()
        disconnect()
        try? await beginDraftFlush().value
    }

    func messages(peerID: String, before: MessageCursor? = nil) async -> [ChatMessage] {
        await messagePage(peerID: peerID, before: before).messages
    }

    func messagePage(peerID: String, before: MessageCursor? = nil) async -> MessagePage {
        do { return try await store?.messagePage(peerID: peerID, before: before) ?? MessagePage(messages: [], hasEarlier: false) }
        catch is CancellationError { return MessagePage(messages: [], hasEarlier: false) }
        catch { show(error); return MessagePage(messages: [], hasEarlier: false) }
    }

    func draftState(peerID: String) -> ConversationDraft {
        if let cached = drafts[peerID] { return cached }
        let state = ConversationDraft()
        drafts[peerID] = state
        return state
    }

    func loadDraft(peerID: String) async {
        let state = draftState(peerID: peerID)
        guard !state.loaded else { return }
        let saved = (try? await store?.draft(peerID: peerID)) ?? ""
        // A second window may have completed loading and started editing during the await.
        guard !state.loaded else { return }
        state.text = saved
        state.loaded = true
    }

    func saveDraft(peerID: String, text: String) {
        let state = draftState(peerID: peerID)
        state.text = text
        state.loaded = true
        let previous = draftWrites[peerID]?.task
        previous?.cancel()
        let id = UUID()
        let store = self.store
        let task = Task.detached { [weak self] in
            // Keep canceled writes in the chain so a late SQLite write cannot overwrite newer text.
            await previous?.value
            let failure: Error?
            do {
                try await Task.sleep(for: .milliseconds(400))
                try Task.checkCancellation()
                try await store?.saveDraft(peerID: peerID, text: text)
                try Task.checkCancellation()
                failure = nil
            } catch is CancellationError { return } catch { failure = error }
            // Persistence must finish without waiting for the main actor during AppKit termination.
            Task { @MainActor [weak self] in
                guard let self, self.draftWrites[peerID]?.id == id else { return }
                self.draftWrites[peerID] = nil
                if let failure { self.show(failure) }
            }
        }
        draftWrites[peerID] = DraftWrite(id: id, task: task)
    }

    func beginDraftFlush() -> Task<Void, Error> {
        let writes = draftWrites.values.map(\.task)
        draftWrites.removeAll()
        for write in writes { write.cancel() }
        let snapshot = drafts.compactMap { id, draft in draft.loaded ? (id, draft.text) : nil }
        let store = self.store
        let flush = Task.detached {
            // Drain writes already inside SQLite before committing the latest snapshots.
            for write in writes { await write.value }
            guard let store else { return }
            for (id, text) in snapshot { try await store.saveDraft(peerID: id, text: text) }
        }
        let id = UUID(), peers = snapshot.map(\.0)
        let barrier = Task.detached { [weak self] () -> Void in
            // Canceling a later debounce must not bypass this durable flush.
            _ = try? await flush.value
            Task { @MainActor [weak self] in
                guard let self else { return }
                for peerID in peers where self.draftWrites[peerID]?.id == id {
                    self.draftWrites[peerID] = nil
                }
            }
        }
        for peerID in peers { draftWrites[peerID] = DraftWrite(id: id, task: barrier) }
        return flush
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
