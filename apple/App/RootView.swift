import SwiftUI
import WozaiCore

private struct FindNearbyEnvironmentKey: EnvironmentKey {
    static let defaultValue: @MainActor () -> Void = {}
}

extension EnvironmentValues {
    var findNearby: @MainActor () -> Void {
        get { self[FindNearbyEnvironmentKey.self] }
        set { self[FindNearbyEnvironmentKey.self] = newValue }
    }
}

#if os(macOS)
private struct FindNearbyFocusedKey: FocusedValueKey {
    typealias Value = @MainActor () -> Void
}

extension FocusedValues {
    var findNearby: (@MainActor () -> Void)? {
        get { self[FindNearbyFocusedKey.self] }
        set { self[FindNearbyFocusedKey.self] = newValue }
    }
}
#endif

@MainActor
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(PublicMeshModel.self) private var mesh
    @Environment(\.scenePhase) private var scenePhase
    @State private var consentPresented = false
    @State private var presentedFileToken: UUID?

    var body: some View {
        @Bindable var model = model
        Group {
            if model.initialized {
                #if os(macOS)
                MacNavigation()
                #else
                MobileNavigation()
                #endif
            } else if let error = model.startupError {
                ContentUnavailableView {
                    Label("Couldn’t open NearbyIM", systemImage: "exclamationmark.triangle")
                } description: { Text(verbatim: error) } actions: {
                    Button("Try again") { Task { await model.bootstrap() } }
                }
            } else { ProgressView("Opening NearbyIM…") }
        }
        .task { await model.bootstrap() }
        #if os(macOS)
        .sheet(item: $model.consent, onDismiss: { consentPresented = false; model.dismissConsent() }) { peer in
            ConsentView(peer: peer)
                .environment(model)
                .presentationDetents([.medium, .large])
                .onAppear { consentPresented = true }
        }
        #endif
        .sheet(item: Binding(get: { consentPresented ? nil : model.fileOfferPresentation }, set: { _ in
            // Reject an interactive dismissal after the native transition, never before a button action.
        }), onDismiss: {
            if let expected = presentedFileToken { model.decideFile(false, expected: expected) }
        }) { prompt in
            VStack(alignment: .leading, spacing: 20) {
                Label("Receive file?", systemImage: "doc.badge.arrow.down").font(.title2)
                Text(verbatim: prompt.info.name).textSelection(.enabled)
                Text(ByteCountFormatter.string(fromByteCount: Int64(prompt.info.size), countStyle: .file)).foregroundStyle(.secondary)
                HStack {
                    Button("Reject", role: .cancel) { model.decideFile(false, expected: prompt.id) }
                    Spacer()
                    Button("Receive") { model.decideFile(true, expected: prompt.id) }.buttonStyle(.borderedProminent)
                }
            }
            .padding(24)
            .presentationDetents([.medium])
            .onAppear { presentedFileToken = prompt.id }
            #if os(macOS)
            .frame(minWidth: 360, minHeight: 220)
            #endif
        }
        .alert("NearbyIM", isPresented: Binding(get: { model.notice != nil }, set: { if !$0 { model.notice = nil } })) {
            Button("OK", role: .cancel) { model.notice = nil }
        } message: { Text(verbatim: model.notice?.message ?? "") }
        .onChange(of: scenePhase, initial: true) { _, phase in
            #if os(iOS)
            if phase == .background { mesh.suspend();Task { await model.suspend() } }
            if phase == .active { mesh.resume();model.resume() }
            #endif
        }
    }
}

#if os(iOS)
private enum AppTab: String, Hashable { case chats, nearby, settings }

@MainActor
private struct MobileNavigation: View {
    @Environment(AppModel.self) private var model
    @SceneStorage("selectedTab") private var tab = AppTab.chats
    @State private var privateDevices = false

    var body: some View {
        Group {
            if #available(iOS 18, *) {
                TabView(selection: $tab) {
                    Tab("Chats", systemImage: "bubble.left.and.bubble.right", value: .chats) { ChatsView(findNearby: findDevices) }
                    Tab("Nearby", systemImage: "antenna.radiowaves.left.and.right", value: .nearby) { nearby }
                    Tab("Settings", systemImage: "gearshape", value: .settings) { NavigationStack { SettingsView() } }
                }
                .tabViewStyle(.sidebarAdaptable)
            } else {
                TabView(selection: $tab) {
                    ChatsView(findNearby: findDevices).tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }.tag(AppTab.chats)
                    nearby.tabItem { Label("Nearby", systemImage: "antenna.radiowaves.left.and.right") }.tag(AppTab.nearby)
                    NavigationStack { SettingsView() }.tabItem { Label("Settings", systemImage: "gearshape") }.tag(AppTab.settings)
                }
            }
        }
        .onChange(of: model.openedConversation) { _, opened in
            if opened != nil { privateDevices = false; tab = .chats }
        }
        .onChange(of: tab) { _, newTab in if newTab != .chats { model.clearViewedConversation() } }
        .environment(\.findNearby, findDevices)
    }

    private var nearby: some View {
        NavigationStack {
            PublicMeshView(onPrivate: { privateDevices = true })
                .navigationDestination(isPresented: $privateDevices) { NearbyView() }
        }
    }

    private func findDevices() {
        tab = .nearby
        privateDevices = true
    }
}

@MainActor
private struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var sizeClass
    @SceneStorage("selectedConversation") private var selection = ""
    @SceneStorage("lastConversationOpening") private var lastOpening = ""
    @State private var visibility = NavigationSplitViewVisibility.all
    @State private var compactColumn = NavigationSplitViewColumn.sidebar
    @State private var pendingOpening: AppModel.ConversationOpening?
    @State private var search = ""
    let findNearby: () -> Void

    var body: some View {
        GeometryReader { geometry in
            NavigationSplitView(columnVisibility: $visibility, preferredCompactColumn: $compactColumn) {
                List(selection: Binding<String?>(get: { selection.isEmpty ? nil : selection }, set: { selection = $0 ?? "" })) {
                    ForEach(model.conversations.filter { search.isEmpty || $0.nickname.localizedCaseInsensitiveContains(search) ||
                        ($0.preview?.localizedCaseInsensitiveContains(search) ?? false) }) { contact in
                        NavigationLink(value: contact.id) { ConversationLabel(contact: contact) }
                            .accessibilityIdentifier("conversation-" + contact.id)
                    }
                }
                .overlay {
                    if model.conversations.isEmpty {
                        ContentUnavailableView {
                            Label("No chats yet", systemImage: "bubble.left.and.bubble.right")
                        } description: {
                            Text("Find a nearby device to start a conversation.")
                        } actions: {
                            Button("Find nearby devices", systemImage: "antenna.radiowaves.left.and.right", action: findNearby)
                                .buttonStyle(.borderedProminent)
                        }
                    }
                }
                .modifier(PrivateRequestInset(enabled: sizeClass == .compact))
                .navigationTitle("NearbyIM")
                .searchable(text: $search, prompt: "Search conversations")
                // The primary column can have compact traits even inside a regular-width iPad window.
                .modifier(AdjustableSidebar(key: "chatColumnFraction", total: geometry.size.width, initial: 300,
                                            minimum: 220, allowsResize: sizeClass == .regular))
            } detail: {
                if let contact = model.conversations.first(where: { $0.id == selection }) {
                    ChatDetailView(contact: contact).id(contact.id)
                } else {
                    ContentUnavailableView("Choose a conversation", systemImage: "bubble.left.and.bubble.right")
                        .modifier(PrivateRequestInset(enabled: sizeClass != .compact))
                }
            }
            .navigationSplitViewStyle(.balanced)
            .onChange(of: geometry.size.width) { _, _ in
                if sizeClass == .regular { visibility = .all }
            }
            .onChange(of: sizeClass) { _, value in if value == .regular { visibility = .all } }
            .onChange(of: compactColumn) { _, column in
                if sizeClass == .compact && column == .sidebar { model.clearViewedConversation() }
            }
        }
        .onChange(of: model.openedConversation, initial: true) { _, opened in
            pendingOpening = opened
            revealConnectedConversation()
        }
        .onChange(of: model.conversations.map(\.id)) { _, _ in revealConnectedConversation() }
    }

    private func revealConnectedConversation() {
        guard let opening = pendingOpening, opening.generation.uuidString != lastOpening,
              model.conversations.contains(where: { $0.id == opening.peerID }) else { return }
        // READY can arrive before the asynchronous conversation reload. Select only a real row,
        // then explicitly reveal detail when the split view is collapsed on iPhone.
        selection = opening.peerID
        compactColumn = .detail
        lastOpening = opening.generation.uuidString
        pendingOpening = nil
    }
}
#endif

#if os(macOS)
@MainActor
private struct MacNavigation: View {
    @Environment(AppModel.self) private var model
    @SceneStorage("sidebarSelection") private var selection = "nearby"

    var body: some View {
        GeometryReader { geometry in
            NavigationSplitView {
                List(selection: Binding<String?>(get: { selection.isEmpty ? nil : selection }, set: { selection = $0 ?? "" })) {
                    Label("Nearby", systemImage: "antenna.radiowaves.left.and.right").tag("nearby").accessibilityIdentifier("nearbyDestination")
                    Label("Public Mesh", systemImage: "point.3.connected.trianglepath.dotted").tag("mesh")
                    Section("Chats") {
                        ForEach(model.conversations) { contact in
                            ConversationLabel(contact: contact).tag("chat:" + contact.id)
                                .accessibilityIdentifier("conversation-" + contact.id)
                        }
                    }
                }
                .navigationTitle("NearbyIM")
                .modifier(AdjustableSidebar(key: "macColumnFraction", total: geometry.size.width, initial: 240, minimum: 180))
            } detail: {
                if let contact = model.conversations.first(where: { "chat:" + $0.id == selection }) {
                    ChatDetailView(contact: contact).id(contact.id)
                } else if selection == "mesh" { PublicMeshView() }
                else { NearbyView() }
            }
            .navigationSplitViewStyle(.balanced)
        }
        .frame(minWidth: 600, minHeight: 420)
        .toolbar {
            ToolbarItem { Button("New chat", systemImage: "square.and.pencil") { selection = "nearby" }.help("Find nearby devices") }
        }
        .onChange(of: model.connectionState) { _, value in
            if value == .connected, let id = model.activePeer?.id { selection = "chat:" + id }
        }
        .environment(\.findNearby, { selection = "nearby" })
        // Discovery commands follow the key scene instead of broadcasting through the shared model.
        .focusedSceneValue(\.findNearby, { selection = "nearby" })
    }
}
#endif

@MainActor
struct ConversationLabel: View {
    @Environment(\.wozaiAccent) private var accent
    let contact: Conversation
    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "person.crop.circle.fill").font(.system(size: 36))
                .symbolRenderingMode(.hierarchical).foregroundStyle(accent).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: contact.nickname).font(.headline).lineLimit(1)
                Group {
                    if let preview = contact.preview, !preview.isEmpty { Text(verbatim: preview) }
                    else { Text("No messages yet") }
                }.font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 8)
            if contact.lastActivity > 0 {
                Text(Date(timeIntervalSince1970: Double(contact.lastActivity) / 1000), style: .date)
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
        }.padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }
}

#if os(iOS)
@MainActor
struct PrivateRequestInset: ViewModifier {
    @Environment(AppModel.self) private var model
    var enabled = true

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .top, spacing: 0) {
            if enabled, let peer = model.consent {
                PrivateRequestBanner(peer: peer, generation: model.verificationGeneration)
            }
        }
    }
}

@MainActor
private struct PrivateRequestBanner: View {
    @Environment(AppModel.self) private var model
    let peer: VerifiedPeer
    let generation: UUID

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Label("Private chat request", systemImage: "person.crop.circle.badge.questionmark")
                .font(.subheadline.weight(.semibold))
                .accessibilityIdentifier("privateRequest")
            Text(verbatim: peer.nickname).font(.headline).lineLimit(1)
            Text("Accept this connection only if you recognize the device. A name is not proof of identity.")
                .font(.caption).foregroundStyle(.secondary)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { actions }
                VStack(alignment: .leading, spacing: 4) { actions }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .frame(maxWidth: 700, alignment: .leading)
        .frame(maxWidth: .infinity)
        .background(.regularMaterial)
    }

    @ViewBuilder private var actions: some View {
        Button(role: .cancel) { model.declineConsent(generation: generation) } label: {
            Text("Decline").frame(minHeight: 44).contentShape(Rectangle())
        }
        Button { model.approve(remember: false, generation: generation) } label: {
            Text("Accept once").frame(minHeight: 44).contentShape(Rectangle())
        }.accessibilityIdentifier("consentOnce")
        Button { model.approve(remember: true, generation: generation) } label: {
            Text("Accept and remember").frame(minHeight: 32)
        }.buttonStyle(.borderedProminent)
    }
}
#endif

@MainActor
private struct ConsentView: View {
    @Environment(AppModel.self) private var model
    let peer: VerifiedPeer
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Device") { Text(verbatim: peer.nickname) }
                    Text("Accept this connection only if you recognize the device. A name is not proof of identity.")
                    Text(verbatim: peer.fingerprint).font(.caption.monospaced()).textSelection(.enabled)
                }
                Section {
                    Button("Accept and remember") { model.approve(remember: true) }
                    Button("Accept once") { model.approve(remember: false) }.accessibilityIdentifier("consentOnce")
                    Button("Decline", role: .cancel) { model.disconnect() }
                } footer: { Text("Remembering a device is separate from comparing its safety code.") }
            }
            .formStyle(.grouped)
            .navigationTitle("Allow this device?")
        }
        #if os(macOS)
        .frame(minWidth: 440, idealWidth: 480, minHeight: 420)
        #endif
    }
}
