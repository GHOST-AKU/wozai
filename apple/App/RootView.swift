import SwiftUI
import WozaiCore

@MainActor
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase

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
        .sheet(item: $model.consent, onDismiss: { model.dismissConsent() }) { peer in
            ConsentView(peer: peer)
                .environment(model)
                .presentationDetents([.medium, .large])
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
private enum AppTab: Hashable { case chats, nearby, settings }

@MainActor
private struct MobileNavigation: View {
    @Environment(AppModel.self) private var model
    @State private var tab = AppTab.chats

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
        .onChange(of: model.newChatRequest) { tab = .nearby }
    }
}

@MainActor
private struct ChatsView: View {
    @Environment(AppModel.self) private var model
    @State private var selection: String?
    let findNearby: () -> Void

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
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
        .onChange(of: model.connectionState, initial: true) { _, value in if value == .connected { selection = model.activePeer?.id } }
    }
}
#endif

#if os(macOS)
@MainActor
private struct MacNavigation: View {
    @Environment(AppModel.self) private var model
    @State private var selection: String? = "nearby"

    var body: some View {
        NavigationSplitView {
            List(selection: $selection) {
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
        .onChange(of: model.newChatRequest) { selection = "nearby" }
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
