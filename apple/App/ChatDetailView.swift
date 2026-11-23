import SwiftUI
import WozaiCore
#if DEBUG
import os
private let timelineLog = Logger(subsystem: "dev.ghost.wozai.apple", category: "Timeline")
#endif
#if os(macOS)
import AppKit
#else
import UIKit
#endif

@MainActor
struct ChatDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.findNearby) private var findNearby
    @Environment(\.scenePhase) private var scenePhase
    #if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
    #endif
    @State private var readVisible = Set<String>()
    @State private var readVisibility = ReadVisibility()
    private var readUnobscured: Bool {
        !safety && !details && !forget && !emoji && model.consent == nil && model.fileOfferPresentation == nil && model.notice == nil
    }
    private var readForeground: Bool {
        #if os(macOS)
        scenePhase == .active && controlActiveState == .key && model.readReceiptsEnabled && readUnobscured
        #else
        scenePhase == .active && model.readReceiptsEnabled && readUnobscured
        #endif
    }

    let contact: Conversation
    @State private var messages: [ChatMessage] = []
    @State private var hasEarlier = false
    private var draft: String {
        get { model.draftState(peerID: contact.id).text }
        nonmutating set { model.saveDraft(peerID: contact.id, text: newValue) }
    }
    private var draftLoaded: Bool { model.draftState(peerID: contact.id).loaded }
    @State private var before: MessageCursor?
    @State private var sending = false
    @State private var safety = false
    @State private var details = false
    @State private var emoji = false
    @State private var forget = false
    @State private var loaded = false
    @State private var displayedPage: MessageCursor?
    @State private var followingLatest = true
    @State private var hasNewMessages = false
    @State private var needsLatestRefresh = false
    @State private var forceLatest = false
    @State private var refreshRequest = 0
    @State private var pageRevision = 0
    @State private var pendingScroll = false
    @State private var userScrolling = false
    @State private var scrollInteraction = 0
    @State private var latestVisible = true
    @FocusState private var composerFocused: Bool
    private var connected: Bool { model.connectionState == .connected && model.activePeer?.id == contact.id }
    private var queued: Bool { model.hasDeferredSend(to: contact.id) }
    private var canReconnect: Bool { model.hasAutomaticRoute(contact) }
    private var sendLabel: LocalizedStringKey { connected ? "Send" : "Send when connected" }
    private var limit: Int { connected ? model.textLimit : TextLimits.legacy }
    private var tooLong: Bool { draft.utf8.count > (connected ? model.textLimit : TextLimits.legacy) }
    private var latestLabel: LocalizedStringKey { hasNewMessages ? "New messages" : "Latest messages" }
    private var refreshKey: String { contact.id + ":" + String(model.revision[contact.id, default: 0]) + ":" + String(before?.sequence ?? 0) + ":" + String(refreshRequest) }
    private var attachments: [(key: String, value: AttachmentRecord)] {
        guard before == nil else { return [] }
        return Array(model.transfers.filter { $0.value.peerID == contact.id && $0.value.root == contact.root }
            .sorted { ($0.value.info.time, $0.key) < ($1.value.info.time, $1.key) }.suffix(50))
    }

    var body: some View {
        timeline
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .onChange(of: attachments.map { $0.key }) { previous, next in
            guard !Set(next).subtracting(previous).isEmpty, before == nil else { return }
            if followingLatest && !userScrolling { pendingScroll = true; pageRevision &+= 1 }
            else { hasNewMessages = true }
        }
        .navigationTitle(contact.nickname)
        #if os(iOS)
        .modifier(PrivateRequestInset())
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Conversation details", systemImage: "info.circle") { readVisibility = ReadVisibility(); details = true }
                    Button("Safety code", systemImage: "lock.shield") { readVisibility = ReadVisibility(); safety = true }.disabled(!connected)
                    if connected { Button("Disconnect", systemImage: "xmark.circle") { model.disconnect() } }
                    if contact.remembered { Button("Forget device", systemImage: "person.crop.circle.badge.minus", role: .destructive) { readVisibility = ReadVisibility(); forget = true } }
                } label: { Label("Conversation actions", systemImage: "ellipsis.circle") }
                .accessibilityIdentifier("conversationActions")
            }
        }
        .task(id: refreshKey) {
            let loaded = await model.messagePage(peerID: contact.id, before: before)
            guard !Task.isCancelled else { return }
            applyPage(loaded)
        }
        .task(id: contact.id + (readForeground ? ":reads-on" : ":reads-off")) {
            readVisibility = ReadVisibility()
            guard readForeground else { return }
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
                let ids = readVisibility.update(peer: contact.id, visible: readVisible, foreground: readForeground && !userScrolling && !pendingScroll)
                if !ids.isEmpty, !(await model.observedMessages(peerID: contact.id, ids: ids)) { readVisibility.retry(ids) }
            }
        }
        .onDisappear { readVisible.removeAll(); readVisibility = ReadVisibility() }
        #if os(iOS)
        .onAppear { model.showConversation(contact) }
        .onDisappear { model.clearViewedConversation(peerID: contact.id) }
        .onChange(of: contact.remembered) { _, _ in model.showConversation(contact) }
        #endif
        .task(id: contact.id) {
            await model.loadDraft(peerID: contact.id)
            await model.loadTransfers(contact)
        }
        .sheet(isPresented: $safety) {
            if let peer = model.activePeer, peer.id == contact.id {
                SafetyView(peer: peer, generation: model.verificationGeneration)
            }
        }
        .sheet(isPresented: $details) { ConversationDetailsView(contact: contact) }
        .sheet(isPresented: $emoji) {
            EmojiPicker { glyph in draft += glyph }
                .presentationDetents([.medium, .large])
        }
        .confirmationDialog("Forget this device?", isPresented: $forget) {
            Button("Forget device", role: .destructive) { Task { await model.forget(contact) } }
            Button("Cancel", role: .cancel) {}
        } message: { Text("The connection will end. Chat history stays on this device.") }
    }

    private var timeline: some View {
        let revision = pageRevision
        return ScrollViewReader { proxy in
            ScrollView {
                // The bounded 200-row page gives scroll targets exact positions during burst inserts.
                VStack(spacing: 12) {
                    if hasEarlier, let first = messages.first {
                        Button("Earlier messages") { followingLatest = false; before = first.cursor }
                            .id("earlier")
                    }
                    if !loaded { ProgressView() }
                    else if messages.isEmpty && attachments.isEmpty {
                        ContentUnavailableView("No messages yet", systemImage: "bubble.left", description: Text("Send a greeting when the devices are connected."))
                    }
                    ForEach(timelineEntries) { entry in
                        switch entry {
                        case .text(let message):
                            MessageBubble(message: message, visibilityChanged: { visible in
                                if visible { readVisible.insert(message.id) } else { readVisible.remove(message.id) }
                            }).id(message.rowID)
                        case .file(_, let record):
                            AttachmentBubble(record: record) {
                                if followingLatest && !userScrolling {
                                    pendingScroll = true
                                    pageRevision &+= 1
                                }
                            }
                        }
                    }
                    Color.clear.frame(height: 1).id("latest")
                        .onGeometryChange(for: TailObservation.self) { geometry in
                            let frame = geometry.frame(in: .scrollView)
                            let viewport = geometry.bounds(of: .scrollView)?.size ?? .zero
                            let height = viewport.height
                            #if DEBUG
                            timelineLog.debug("tail top=\(Double(frame.minY), privacy: .public) bottom=\(Double(frame.maxY), privacy: .public) viewport=\(Double(height), privacy: .public) revision=\(revision, privacy: .public)")
                            #endif
                            return TailObservation(visible: height > 0 && frame.minY >= 0 && frame.maxY <= height + 24, revision: revision, viewport: viewport)
                        } action: { observation in
                            guard observation.revision == pageRevision else { return }
                            latestVisible = observation.visible
                            guard loaded, observation.viewport.height > 0 else { return }
                            // File prompts, thumbnails and keyboard layout can displace the tail.
                            // Detach only when the reader actually scrolls away.
                            if followingLatest && before == nil && !userScrolling && !observation.visible { pendingScroll = true }
                            if pendingScroll && !userScrolling {
                                if before != nil {
                                    pendingScroll = false
                                    if let first = messages.first { proxy.scrollTo(first.rowID, anchor: .top) }
                                } else if observation.visible {
                                    pendingScroll = false
                                    followingLatest = true
                                } else {
                                    // Geometry observation runs after the current page has measured its targets.
                                    proxy.scrollTo("latest", anchor: .bottom)
                                }
                            } else { updateFollowing() }
                        }
                }
                .frame(maxWidth: 820)
                .padding(.horizontal, 16).padding(.vertical, 12)
                .frame(maxWidth: .infinity)
            }
            .accessibilityIdentifier("messageTimeline")
            .contentShape(Rectangle())
            .scrollDismissesKeyboard(.immediately)
            .scrollBounceBehavior(.always, axes: .vertical)
            .modifier(UserScrollObserver(began: {
                readVisibility = ReadVisibility()
                if !userScrolling { scrollInteraction &+= 1 }
                userScrolling = true; pendingScroll = false; forceLatest = false; followingLatest = false
            }, ended: {
                readVisibility = ReadVisibility()
                userScrolling = false; updateFollowing()
            }))
            .overlay(alignment: .bottomTrailing) {
                if !followingLatest || before != nil || hasNewMessages {
                    Button(action: showLatest) {
                        Image(systemName: "arrow.down").font(.body.weight(.semibold))
                            .frame(minWidth: 30, minHeight: 30)
                    }
                    .buttonStyle(.bordered)
                    .buttonBorderShape(.circle)
                    .accessibilityLabel(latestLabel)
                    .accessibilityIdentifier("jumpToLatest")
                    .padding()
                }
            }
        }
    }

    private var timelineEntries: [PrivateTimelineEntry] {
        // Preserve local insertion order for text, even when a remote clock is wrong.
        var filesAfterIndex: [Int: [PrivateTimelineEntry]] = [:]
        for (key, attachment) in attachments {
            let index = messages.lastIndex { $0.timestamp <= attachment.info.time } ?? -1
            filesAfterIndex[index, default: []].append(.file(key, attachment))
        }
        return filesAfterIndex[-1, default: []] + messages.enumerated().flatMap { index, message in
            [.text(message)] + filesAfterIndex[index, default: []]
        }
    }

    private func updateFollowing() {
        guard loaded, before == nil, !pendingScroll, !userScrolling else { return }
        followingLatest = latestVisible
        if latestVisible && hasNewMessages {
            if needsLatestRefresh { showLatest() }
            else { hasNewMessages = false }
        }
    }

    private func applyPage(_ result: MessagePage) {
        let page = result.messages
        let pageChanged = displayedPage != before
        if loaded, before == nil, (!followingLatest || userScrolling), !forceLatest {
            // Keep the existing rows at the same offsets and append newer rows below them.
            let updates = Dictionary(uniqueKeysWithValues: page.map { ($0.rowID, $0) })
            let tail = messages.last?.cursor.sequence ?? 0
            let newer = page.filter { $0.cursor.sequence > tail }
            hasNewMessages = hasNewMessages || !newer.isEmpty
            messages = messages.map { updates[$0.rowID] ?? $0 }
            if messages.count + newer.count <= 400 { messages.append(contentsOf: newer) }
            else { needsLatestRefresh = true }
        } else {
            let tailChanged = !loaded || pageChanged || forceLatest || messages.last?.rowID != page.last?.rowID
            messages = page
            hasEarlier = result.hasEarlier
            if before == nil {
                followingLatest = true; hasNewMessages = false; needsLatestRefresh = false; forceLatest = false
                if tailChanged { pendingScroll = true; pageRevision &+= 1 }
            } else if pageChanged {
                pendingScroll = true; pageRevision &+= 1
            }
        }
        readVisible.formIntersection(Set(messages.filter { !$0.outgoing }.map(\.id)))
        displayedPage = before
        loaded = true
    }

    private func showLatest() {
        forceLatest = true; followingLatest = true; hasNewMessages = false
        before = nil
        refreshRequest += 1
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 6) {
            if queued {
                HStack(alignment: .firstTextBaseline) {
                    Label("Waiting to send · Still on this device", systemImage: "clock")
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Button("Cancel") { model.cancelDeferred(keepNotice: true) }
                }.font(.footnote)
            } else if !connected {
                HStack(alignment: .firstTextBaseline) {
                    Group {
                        if model.connectingPeerID == contact.id { Label("Connecting…", systemImage: "arrow.triangle.2.circlepath") }
                        else if let state = model.privateStatus { Text(verbatim: state) }
                        else { Label("Not connected · Draft stays here", systemImage: "wifi.slash") }
                    }.foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    #if os(iOS)
                    if model.connectingPeerID == contact.id {
                        Button("Cancel") { model.disconnect() }
                    } else if contact.remembered {
                        if canReconnect {
                            Button("Reconnect") { model.retryAutomaticConnection(contact) }
                        } else {
                            Button("Enable auto-connect") { model.retryAutomaticConnection(contact) }
                        }
                    } else { Button("Find nearby devices", action: findNearby) }
                    #else
                    Button("Find nearby devices", action: findNearby)
                    #endif
                }.font(.caption)
            }
            if tooLong {
                Text("This draft exceeds the connection limit. Shorten it or send a file. Your draft is preserved.")
                    .font(.caption).foregroundStyle(.red)
            }
            HStack(alignment: .bottom, spacing: 6) {
                AttachmentControls(peerID: contact.id)
                TextField("Message", text: Binding(get: { draft }, set: { draft = $0 }), axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.plain)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .frame(minHeight: 44)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22))
                    .overlay { RoundedRectangle(cornerRadius: 22).strokeBorder(.quaternary) }
                    .focused($composerFocused)
                    .disabled(!draftLoaded)
                    .accessibilityIdentifier("composer")
                Button {
                    composerFocused = false
                    emoji = true
                } label: {
                    Label("Emoji", systemImage: "face.smiling").labelStyle(.iconOnly)
                        .font(.title3).frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderless).disabled(!draftLoaded)
                .accessibilityIdentifier("emojiPicker")
                Button { Task { await send() } } label: {
                    Label(sendLabel, systemImage: connected ? "arrow.up.circle.fill" : "clock.arrow.circlepath")
                        .labelStyle(.iconOnly).font(.system(size: 30))
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .keyboardShortcut(.return, modifiers: .command)
                .help(sendLabel)
                .accessibilityHint(connected ? Text("Send to this device.") : Text("Tries for up to one minute. Otherwise the text stays in your draft."))
                .disabled((!connected && !canReconnect) || !draftLoaded || queued || sending || !TextLimits.canSend(draft, limit: limit))
                .accessibilityIdentifier("sendMessage")
            }
        }
        .frame(maxWidth: 820)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    private func send() async {
        guard !sending else { return }
        let snapshot = draft
        if !connected { model.queueText(snapshot, to: contact); return }
        let interaction = scrollInteraction
        sending = true
        let saved = await model.send(snapshot, peerID: contact.id)
        if saved {
            if draft == snapshot { draft = "" }
            if interaction == scrollInteraction && !userScrolling { showLatest() }
        }
        sending = false
    }
}

private enum PrivateTimelineEntry: Identifiable {
    case text(ChatMessage), file(String, AttachmentRecord)

    var id: String {
        switch self {
        case .text(let message): message.rowID
        case .file(let key, _): "file-" + key
        }
    }
}

private struct TailObservation: Equatable, Sendable {
    let visible: Bool
    let revision: Int
    let viewport: CGSize
}

@MainActor
private struct UserScrollObserver: ViewModifier {
    let began: @MainActor () -> Void
    let ended: @MainActor () -> Void
    @GestureState private var dragging = false

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 18, macOS 15, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .interacting { began() }
                else if phase == .idle { ended() }
            }
        } else {
            content.simultaneousGesture(DragGesture(minimumDistance: 2).updating($dragging) { _, active, _ in
                active = true
            })
            .onChange(of: dragging) { _, active in if active { began() } else { ended() } }
        }
    }
}

@MainActor
private struct MessageBubble: View {
    @Environment(\.wozaiAccent) private var accent
    let message: ChatMessage
    var visibilityChanged: (Bool) -> Void = { _ in }
    private var time: Date { Date(timeIntervalSince1970: Double(message.timestamp) / 1000) }
    private var status: LocalizedStringKey {
        switch message.status {
        case .pending: return "Sending"
        case .sent: return "Sent"
        case .delivered: return "Saved on other device"
        case .read: return "Read"
        case .unconfirmed: return "Unconfirmed"
        }
    }
    private var symbol: String {
        switch message.status {
        case .pending: return "clock"
        case .sent: return "checkmark"
        case .delivered, .read: return "checkmark.circle"
        case .unconfirmed: return "questionmark.circle"
        }
    }
    var body: some View {
        HStack {
            if message.outgoing { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: message.body).textSelection(.enabled)
                    .onGeometryChange(for: Bool.self) { geometry in
                        let frame = geometry.frame(in: .scrollView)
                        let height = geometry.bounds(of: .scrollView)?.height ?? 0
                        return !message.outgoing && ReadVisibility.visible(top: Double(frame.minY), height: Double(frame.height), viewportHeight: Double(height))
                    } action: { visibilityChanged($0) }
                HStack(spacing: 8) {
                    Text(time, format: .dateTime.hour().minute())
                    if message.outgoing {
                        Label(status, systemImage: symbol)
                        .foregroundStyle(message.status == .read ? accent : Color.secondary)
                        .accessibilityIdentifier("deliveryStatus")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(message.outgoing ? accent.opacity(0.14) : Color.secondary.opacity(0.10), in: RoundedRectangle(cornerRadius: 16))
            .contextMenu {
                Button("Copy message", systemImage: "doc.on.doc") {
                    #if os(macOS)
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(message.body, forType: .string)
                    #else
                    UIPasteboard.general.string = message.body
                    #endif
                }
                ShareLink(item: message.body) { Label("Share message", systemImage: "square.and.arrow.up") }
            }
            .frame(maxWidth: 520, alignment: message.outgoing ? .trailing : .leading)
            if !message.outgoing { Spacer(minLength: 48) }
        }
    }
}

@MainActor
private struct ConversationDetailsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let contact: Conversation

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(verbatim: contact.nickname)
                    if model.connectionState == .connected, let peer = model.activePeer, peer.id == contact.id {
                        Label("End-to-end encrypted", systemImage: "lock.fill").accessibilityIdentifier("readyStatus")
                        LocalPathLabel(path: model.connectionPath)
                    } else { Text("Not connected").foregroundStyle(.secondary) }
                    if contact.verified { Text("Identity verified").foregroundStyle(.secondary) }
                }
                Section {
                    ShareLink(item: model.networkDiagnostics) {
                        Label("Share connection diagnostics", systemImage: "square.and.arrow.up")
                    }
                }
            }
            .navigationTitle("Conversation details")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 420, minHeight: 360)
        #endif
    }
}

@MainActor
private struct SafetyView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let peer: VerifiedPeer
    let generation: UUID

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(verbatim: peer.nickname).font(.headline)
                    Text("Compare the entire code on both devices. An encrypted connection alone does not verify who owns the device.")
                }
                if let identity = model.identity, let code = try? SafetyCode.make(identity.root, peer.root) {
                    VerificationQRControls(peer: peer, generation: generation)
                    Section("Safety code") {
                        Text(verbatim: grouped(code)).font(.body.monospaced()).textSelection(.enabled)
                        ShareLink(item: "WOZAI-ID1:" + code) { Label("Share code", systemImage: "square.and.arrow.up") }
                    }
                    Section {
                        Button("Codes match") { Task { await model.verify(peer, generation: generation); dismiss() } }
                            .disabled(model.activePeer != peer || model.verificationGeneration != generation || model.connectionState != .connected)
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Verify identity")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
        }
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 420)
        #endif
    }

    private func grouped(_ value: String) -> String {
        stride(from: 0, to: value.count, by: 8).map { offset in
            let start = value.index(value.startIndex, offsetBy: offset)
            return String(value[start..<value.index(start, offsetBy: 8)])
        }.joined(separator: " ")
    }
}
