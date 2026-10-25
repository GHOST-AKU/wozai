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
        .sheet(item: $model.consent, onDismiss: { consentPresented = false; model.dismissConsent() }) { peer in
            ConsentView(peer: peer)
                .environment(model)
                .presentationDetents([.medium, .large])
                .onAppear { consentPresented = true }
        }
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
        .onChange(of: scenePhase) { _, phase in
            #if os(iOS)
            if phase == .background { Task { await model.suspend() } }
            if phase == .active { model.resume() }
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

    var body: some View {
        Group {
            if #available(iOS 18, *) {
                TabView(selection: $tab) {
                    Tab("Chats", systemImage: "bubble.left.and.bubble.right", value: .chats) { ChatsView { tab = .nearby } }
                    Tab("Nearby", systemImage: "antenna.radiowaves.left.and.right", value: .nearby) { NavigationStack { NearbyView() } }
                    Tab("Settings", systemImage: "gearshape", value: .settings) { NavigationStack { SettingsView() } }
                }
                .tabViewStyle(.sidebarAdaptable)
            } else {
                TabView(selection: $tab) {
                    ChatsView { tab = .nearby }.tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }.tag(AppTab.chats)
                    NavigationStack { NearbyView() }.tabItem { Label("Nearby", systemImage: "antenna.radiowaves.left.and.right") }.tag(AppTab.nearby)
                    NavigationStack { SettingsView() }.tabItem { Label("Settings", systemImage: "gearshape") }.tag(AppTab.settings)
                }
            }
        }
        .onChange(of: model.connectionState) { _, value in if value == .connected { tab = .chats } }
        .environment(\.findNearby, { tab = .nearby })
    }
}

@MainActor
private struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @SceneStorage("selectedConversation") private var selection = ""
    let findNearby: () -> Void

    var body: some View {
        NavigationSplitView {
            List(selection: Binding<String?>(get: { selection.isEmpty ? nil : selection }, set: { selection = $0 ?? "" })) {
                ForEach(model.conversations) { contact in
                    NavigationLink(value: contact.id) { ConversationLabel(contact: contact) }
                }
            }
            .overlay {
                if model.conversations.isEmpty {
                    ContentUnavailableView("No chats yet", systemImage: "bubble.left.and.bubble.right", description: Text("Find a nearby device to start a conversation."))
                }
            }
            .navigationTitle("Chats")
            .toolbar { Button("New chat", systemImage: "square.and.pencil", action: findNearby) }
            .navigationSplitViewColumnWidth(min: 220, ideal: 300, max: 380)
        } detail: {
            if let contact = model.conversations.first(where: { $0.id == selection }) {
                ChatDetailView(contact: contact).id(contact.id)
            } else { ContentUnavailableView("Choose a conversation", systemImage: "bubble.left.and.bubble.right") }
        }
        .onChange(of: model.connectionState, initial: true) { _, value in if value == .connected { selection = model.activePeer?.id ?? "" } }
    }
}
#endif

#if os(macOS)
@MainActor
private struct MacNavigation: View {
    @Environment(AppModel.self) private var model
    @SceneStorage("sidebarSelection") private var selection = "nearby"

    var body: some View {
        NavigationSplitView {
            List(selection: Binding<String?>(get: { selection.isEmpty ? nil : selection }, set: { selection = $0 ?? "" })) {
                Label("Nearby", systemImage: "antenna.radiowaves.left.and.right").tag("nearby").accessibilityIdentifier("nearbyDestination")
                Section("Chats") {
                    ForEach(model.conversations) { contact in
                        ConversationLabel(contact: contact).tag("chat:" + contact.id)
                    }
                }
            }
            .navigationTitle("NearbyIM")
            .navigationSplitViewColumnWidth(min: 180, ideal: 240, max: 340)
        } detail: {
            if let contact = model.conversations.first(where: { "chat:" + $0.id == selection }) {
                ChatDetailView(contact: contact).id(contact.id)
            } else { NearbyView() }
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
    let contact: Conversation
    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(verbatim: contact.nickname).lineLimit(1)
                if contact.verified { Text("Identity verified").font(.caption).foregroundStyle(.secondary) }
                else if contact.remembered { Text("Remembered device").font(.caption).foregroundStyle(.secondary) }
            }
        } icon: { Image(systemName: "person.crop.circle").imageScale(.large) }
        .accessibilityElement(children: .combine)
    }
}

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
