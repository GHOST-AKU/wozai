import SwiftUI
import WozaiCore
#if os(macOS)
import AppKit
#else
import UIKit
#endif

@MainActor
struct ChatDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.findNearby) private var findNearby
    let contact: Conversation
    @State private var messages: [ChatMessage] = []
    @State private var draft = ""
    @State private var draftLoaded = false
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
    @State private var scrollRequest = 0
    @State private var scrollTarget = "latest"
    @State private var visibleMessage: String?
    @FocusState private var composerFocused: Bool
    private var connected: Bool { model.connectionState == .connected && model.activePeer?.id == contact.id }
    private var tooLong: Bool { draft.utf8.count > 8192 }
    private var latestLabel: LocalizedStringKey { hasNewMessages ? "New messages" : "Latest messages" }
    private var refreshKey: String { contact.id + ":" + String(model.revision[contact.id, default: 0]) + ":" + String(before?.timestamp ?? UInt64.max) + ":" + String(before?.sequence ?? 0) + ":" + String(refreshRequest) }

    var body: some View {
        timeline
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
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
            let loaded = await model.messages(peerID: contact.id, before: before)
            guard !Task.isCancelled else { return }
            applyPage(loaded)
        }
        .task(id: contact.id) {
            let saved = await model.loadDraft(peerID: contact.id)
            guard !Task.isCancelled else { return }
            draft = saved; draftLoaded = true
        }
        .onChange(of: draft) { _, text in model.saveDraft(peerID: contact.id, text: text) }
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
        GeometryReader { viewport in
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        LazyVStack(spacing: 12) {
                            if messages.count == 200, let first = messages.first {
                                Button("Earlier messages") { followingLatest = false; before = first.cursor }
                                    .id("earlier")
                            }
                            if !loaded { ProgressView() }
                            else if messages.isEmpty {
                                ContentUnavailableView("No messages yet", systemImage: "bubble.left", description: Text("Send a greeting when the devices are connected."))
                            }
                            ForEach(messages, id: \.rowID) { message in MessageBubble(message: message) }
                        }
                        .scrollTargetLayout()
                        Color.clear.frame(height: 1).id("latest")
                            .background {
                                GeometryReader { geometry in
                                    Color.clear.preference(key: TimelineBottomKey.self, value: geometry.frame(in: .named("timeline")).maxY)
                                }
                            }
                    }
                    .padding()
                }
                .coordinateSpace(name: "timeline")
                .accessibilityIdentifier("messageTimeline")
                .defaultScrollAnchor(.bottom)
                .scrollPosition(id: $visibleMessage, anchor: .top)
                .scrollDismissesKeyboard(.interactively)
                .onPreferenceChange(TimelineBottomKey.self) { bottom in
                    guard loaded, before == nil else { return }
                    let atBottom = bottom <= viewport.size.height + 24 && bottom >= 0
                    if atBottom && !followingLatest {
                        followingLatest = true
                        if hasNewMessages { showLatest() }
                    } else if !atBottom { followingLatest = false }
                }
                .onChange(of: scrollRequest) {
                    proxy.scrollTo(scrollTarget, anchor: scrollTarget == "latest" ? .bottom : .top)
                }
                .onChange(of: viewport.size) {
                    if followingLatest && before == nil { proxy.scrollTo("latest", anchor: .bottom) }
                }
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
    }

    private func applyPage(_ page: [ChatMessage]) {
        let pageChanged = displayedPage != before
        if loaded, before == nil, !followingLatest, !forceLatest {
            // Keep a bounded, stable page while reading; delivery updates still reach visible rows.
            let updates = Dictionary(uniqueKeysWithValues: page.map { ($0.rowID, $0) })
            let current = Set(messages.map(\.rowID))
            hasNewMessages = hasNewMessages || page.contains { !current.contains($0.rowID) }
            messages = messages.map { updates[$0.rowID] ?? $0 }
        } else {
            messages = page
            if before == nil {
                followingLatest = true; hasNewMessages = false; forceLatest = false
                scrollTarget = "latest"; scrollRequest += 1
            } else if pageChanged, let first = page.first {
                scrollTarget = first.rowID; scrollRequest += 1
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
            } else {
                HStack {
                    Text("Not connected").foregroundStyle(.secondary)
                    Spacer()
                    Button("Find nearby devices", action: findNearby)
                }.font(.caption)
            }
            if tooLong { Text("Keep the message within 8192 UTF-8 bytes. Your draft is preserved.").font(.caption).foregroundStyle(.red) }
            HStack(alignment: .bottom) {
                TextField("Message", text: $draft, axis: .vertical)
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
                .disabled(!connected || !draftLoaded || sending || tooLong || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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

private struct TimelineBottomKey: PreferenceKey {
    static let defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

@MainActor
private struct MessageBubble: View {
    let message: ChatMessage
    private var time: Date { Date(timeIntervalSince1970: Double(message.timestamp) / 1000) }
    private var status: LocalizedStringKey {
        switch message.status {
        case .pending: return "Sending"
        case .sent: return "Sent"
        case .delivered: return "Delivered"
        case .unconfirmed: return "Unconfirmed"
        }
    }
    private var symbol: String {
        switch message.status {
        case .pending: return "clock"
        case .sent: return "checkmark"
        case .delivered: return "checkmark.circle"
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
                    if message.outgoing { Label(status, systemImage: symbol).accessibilityIdentifier("deliveryStatus") }
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
