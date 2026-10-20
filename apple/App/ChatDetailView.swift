import SwiftUI
import WozaiCore

@MainActor
struct ChatDetailView: View {
    @Environment(AppModel.self) private var model
    let contact: Conversation
    @State private var messages: [ChatMessage] = []
    @State private var draft = ""
    @State private var before: UInt64?
    @State private var sending = false
    @State private var safety = false
    @State private var forget = false
    private var connected: Bool { model.connectionState == .connected && model.activePeer?.id == contact.id }
    private var tooLong: Bool { draft.utf8.count > 8192 }
    private var refreshKey: String { contact.id + ":" + String(model.revision[contact.id, default: 0]) + ":" + String(before ?? UInt64.max) }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    if messages.count == 200, let first = messages.first {
                        Button("Earlier messages") { before = first.timestamp }
                    }
                    if before != nil { Button("Latest messages") { before = nil } }
                    if messages.isEmpty {
                        ContentUnavailableView("No messages yet", systemImage: "bubble.left", description: Text("Send a greeting when the devices are connected."))
                    }
                    ForEach(messages, id: \.rowID) { message in MessageBubble(message: message) }
                    Color.clear.frame(height: 1).id("latest")
                }
                .padding()
            }
            .scrollDismissesKeyboard(.interactively)
            .onChange(of: messages.last?.rowID) { if before == nil { proxy.scrollTo("latest", anchor: .bottom) } }
        }
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
            messages = loaded
        }
        .task(id: contact.id) { draft = await model.loadDraft(peerID: contact.id) }
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

    private var composer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if connected {
                Label("End-to-end encrypted", systemImage: "lock.fill").font(.caption).foregroundStyle(.secondary).accessibilityIdentifier("readyStatus")
            } else {
                HStack {
                    Text("Not connected").foregroundStyle(.secondary)
                    Spacer()
                    Button("Find nearby devices") { model.newChatRequest += 1 }
                }.font(.caption)
            }
            if tooLong { Text("Keep the message within 8192 UTF-8 bytes. Your draft is preserved.").font(.caption).foregroundStyle(.red) }
            HStack(alignment: .bottom) {
                TextField("Message", text: $draft, axis: .vertical)
                    .lineLimit(1...6)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("composer")
                Button { Task { await send() } } label: {
                    Label("Send", systemImage: "arrow.up.circle.fill").labelStyle(.iconOnly).font(.title2)
                }
                .keyboardShortcut(.return, modifiers: .command)
                .help("Send message")
                .disabled(!connected || sending || tooLong || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
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
        if saved && draft == snapshot { draft = "" }
        sending = false
    }
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
