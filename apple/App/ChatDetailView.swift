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
    private var readForeground: Bool {
        #if os(macOS)
        scenePhase == .active && controlActiveState == .key && model.readReceiptsEnabled
        #else
        scenePhase == .active && model.readReceiptsEnabled
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
    @State private var forget = false
    @State private var loaded = false
    @State private var displayedPage: MessageCursor?
    @State private var followingLatest = true
    @State private var hasNewMessages = false
    @State private var forceLatest = false
    @State private var refreshRequest = 0
    @State private var pageRevision = 0
    @State private var pendingScroll = false
    @State private var userScrolling = false
    @State private var latestVisible = true
    @State private var observedViewport = CGSize.zero
    @FocusState private var composerFocused: Bool
    private var connected: Bool { model.connectionState == .connected && model.activePeer?.id == contact.id }
    private var tooLong: Bool { draft.utf8.count > (connected ? model.textLimit : TextLimits.legacy) }
    private var latestLabel: LocalizedStringKey { hasNewMessages ? "New messages" : "Latest messages" }
    private var refreshKey: String { contact.id + ":" + String(model.revision[contact.id, default: 0]) + ":" + String(before?.sequence ?? 0) + ":" + String(refreshRequest) }

    var body: some View {
        timeline
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .onChange(of: model.transferRevision[contact.id, default: 0]) { _, _ in
            if followingLatest && before == nil { pendingScroll = true; pageRevision &+= 1 }
            else { hasNewMessages = true }
        }
        .navigationTitle(contact.nickname)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button("Safety code", systemImage: "lock.shield") { safety = true }.disabled(!connected)
                    if connected { Button("Disconnect", systemImage: "xmark.circle") { model.disconnect() } }
                    if contact.remembered { Button("Forget device", systemImage: "person.crop.circle.badge.minus", role: .destructive) { forget = true } }
                } label: { Label("Conversation actions", systemImage: "ellipsis.circle") }
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
        .task(id: contact.id) {
            await model.loadDraft(peerID: contact.id)
        }
        .sheet(isPresented: $safety) {
            if let peer = model.activePeer, peer.id == contact.id {
                SafetyView(peer: peer, generation: model.verificationGeneration).environment(model)
            }
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
                    else if messages.isEmpty {
                        ContentUnavailableView("No messages yet", systemImage: "bubble.left", description: Text("Send a greeting when the devices are connected."))
                    }
                    ForEach(messages, id: \.rowID) { message in
                        MessageBubble(message: message).id(message.rowID)
                            .onGeometryChange(for: Bool.self) { geometry in
                                let frame = geometry.frame(in: .scrollView)
                                let height = geometry.bounds(of: .scrollView)?.height ?? 0
                                return !message.outgoing && ReadVisibility.visible(top: Double(frame.minY), height: Double(frame.height), viewportHeight: Double(height))
                            } action: { visible in
                                if visible { readVisible.insert(message.id) } else { readVisible.remove(message.id) }
                            }
                    }
                    AttachmentTimeline(contact: contact)
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
                            let resized = observedViewport != observation.viewport
                            observedViewport = observation.viewport
                            latestVisible = observation.visible
                            guard loaded, observation.viewport.height > 0 else { return }
                            if resized && followingLatest && before == nil && !userScrolling { pendingScroll = true }
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
                .padding()
            }
            .accessibilityIdentifier("messageTimeline")
            .defaultScrollAnchor(.bottom)
            .scrollDismissesKeyboard(.interactively)
            .scrollBounceBehavior(.always, axes: .vertical)
            .modifier(UserScrollObserver(began: {
                userScrolling = true; pendingScroll = false; forceLatest = false; followingLatest = false
            }, ended: {
                userScrolling = false; updateFollowing()
            }))
            .overlay(alignment: .bottomTrailing) {
                if !followingLatest || before != nil || hasNewMessages {
                    Button(action: showLatest) {
                        Label(latestLabel, systemImage: "arrow.down")
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("jumpToLatest")
                    .padding()
                }
            }
        }
    }

    private func updateFollowing() {
        guard loaded, before == nil, !pendingScroll, !userScrolling else { return }
        followingLatest = latestVisible
        if latestVisible && hasNewMessages { showLatest() }
    }

    private func applyPage(_ result: MessagePage) {
        let page = result.messages
        readVisible.formIntersection(Set(page.filter { !$0.outgoing }.map(\.id)))
        let pageChanged = displayedPage != before
        if loaded, before == nil, !followingLatest, !forceLatest {
            // Keep a bounded, stable page while reading; delivery updates still reach visible rows.
            let updates = Dictionary(uniqueKeysWithValues: page.map { ($0.rowID, $0) })
            let current = Set(messages.map(\.rowID))
            hasNewMessages = hasNewMessages || page.contains { !current.contains($0.rowID) }
            messages = messages.map { updates[$0.rowID] ?? $0 }
        } else {
            messages = page
            hasEarlier = result.hasEarlier
            if before == nil {
                followingLatest = true; hasNewMessages = false; forceLatest = false
                pendingScroll = true; pageRevision &+= 1
            } else if pageChanged {
                pendingScroll = true; pageRevision &+= 1
            }
        }
        displayedPage = before
        loaded = true
    }

    private func showLatest() {
        forceLatest = true; followingLatest = true; hasNewMessages = false
        before = nil
        refreshRequest += 1
    }

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if connected {
                Label("End-to-end encrypted", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("readyStatus")
                LocalPathLabel(path: model.connectionPath)
            } else {
                HStack {
                    Text("Not connected").foregroundStyle(.secondary)
                    Spacer()
                    Button("Find nearby devices", action: findNearby)
                }.font(.caption)
            }
            if !draft.isEmpty {
                Text("\(draft.utf8.count) / \(connected ? model.textLimit : TextLimits.legacy) UTF-8 bytes").font(.caption).foregroundStyle(tooLong ? .red : .secondary)
                if tooLong { Text("This draft exceeds the connection limit. Shorten it or send a file. Your draft is preserved.").font(.caption).foregroundStyle(.red) }
            }
            HStack(alignment: .bottom) {
                AttachmentControls()
                TextField("Message", text: Binding(get: { draft }, set: { draft = $0 }), axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.roundedBorder)
                    .focused($composerFocused)
                    .disabled(!draftLoaded)
                    .accessibilityIdentifier("composer")
                Button { Task { await send() } } label: {
                    Label("Send", systemImage: "arrow.up.circle.fill").labelStyle(.iconOnly).font(.title2)
                }
                .keyboardShortcut(.return, modifiers: .command)
                .help("Send message")
                .disabled(!connected || !draftLoaded || sending || tooLong || !TextLimits.canSend(draft, limit: connected ? model.textLimit : TextLimits.legacy))
                .accessibilityIdentifier("sendMessage")
            }
        }
        .padding()
        .background(.bar)
    }

    private func send() async {
        guard !sending else { return }
        let snapshot = draft
        sending = true
        let saved = await model.send(snapshot, peerID: contact.id)
        if saved {
            if draft == snapshot { draft = "" }
            showLatest()
            composerFocused = true
        }
        sending = false
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

    @ViewBuilder
    func body(content: Content) -> some View {
        if #available(iOS 18, macOS 15, *) {
            content.onScrollPhaseChange { _, phase in
                if phase == .interacting { began() }
                else if phase == .idle { ended() }
            }
        } else {
            content.simultaneousGesture(DragGesture(minimumDistance: 2)
                .onChanged { _ in began() }
                .onEnded { _ in ended() })
        }
    }
}

@MainActor
private struct MessageBubble: View {
    @Environment(\.colorScheme) private var colorScheme
    private let readColor = Color(red: 102.0 / 255, green: 204.0 / 255, blue: 1)
    let message: ChatMessage
    private var time: Date { Date(timeIntervalSince1970: Double(message.timestamp) / 1000) }
    private var status: LocalizedStringKey {
        switch message.status {
        case .pending: return "Sending"
        case .sent: return "Sent"
        case .delivered: return "Delivered"
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
            if message.outgoing { Spacer(minLength: 32) }
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: message.body).textSelection(.enabled)
                HStack(spacing: 8) {
                    Text(time, format: .dateTime.hour().minute())
                    if message.outgoing {
                        Label { Text(status) } icon: {
                            Image(systemName: symbol)
                                .shadow(color: message.status == .read && colorScheme == .dark ? readColor.opacity(0.35) : .clear, radius: 1.5, x: 0, y: 0)
                        }
                        .foregroundStyle(message.status == .read ? readColor : Color.secondary)
                        .accessibilityIdentifier("deliveryStatus")
                    }
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
            .padding(12)
            .background(message.outgoing ? Color.accentColor.opacity(0.12) : Color.secondary.opacity(0.09), in: RoundedRectangle(cornerRadius: 16))
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
            .frame(maxWidth: 640, alignment: message.outgoing ? .trailing : .leading)
            if !message.outgoing { Spacer(minLength: 32) }
        }
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
