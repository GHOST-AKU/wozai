import SwiftUI
import WozaiCore

@MainActor
struct NearbyView: View {
    @Environment(AppModel.self) private var model
    @State private var host = ""
    @State private var port = ""

    var body: some View {
        List {
            Section {
                Toggle("Discover and receive", isOn: Binding(get: { model.discoveryEnabled }, set: { model.setDiscovery($0) }))
            } footer: { Text("Use the same local network, or keep Wi-Fi and Bluetooth enabled for nearby Apple peer-to-peer discovery. Only enable receiving when you want to be found.") }
            if model.discoveryEnabled {
                Section("Nearby devices") {
                    if model.nearby.isEmpty {
                        HStack { ProgressView().controlSize(.small); Text("Looking for devices…").foregroundStyle(.secondary) }
                    }
                    ForEach(model.nearby) { peer in
                        Button { model.connect(peer) } label: { Label(peer.nickname, systemImage: "desktopcomputer.and.iphone") }
                            .disabled(model.busy)
                    }
                }
                if !model.addresses.isEmpty {
                    Section("Your addresses") {
                        ForEach(model.addresses, id: \.self) { Text(verbatim: $0).font(.callout.monospaced()).textSelection(.enabled) }
                    }
                }
            }
            if let error = model.networkError {
                Section {
                    Label("Check local network access", systemImage: "exclamationmark.triangle")
                    Text("Allow NearbyIM to access the local network in System Settings, and check that devices can reach each other.")
                    Text(verbatim: error).font(.caption).foregroundStyle(.secondary)
                    Button("Try again") { model.reconnectDiscovery() }
                }
            }
            Section("Connect by address") {
                TextField("IP address or .local name", text: $host)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("manualHost")
                TextField("Port", text: $port).accessibilityIdentifier("manualPort")
                Button("Connect") { model.connect(host: host, port: port) }
                    .disabled(model.busy || host.isEmpty || port.isEmpty)
                    .accessibilityIdentifier("manualConnect")
            }
            if model.connectionState == .connecting || model.connectionState == .awaitingConsent {
                Section { ProgressView("Connecting…"); Button("Cancel", role: .cancel) { model.disconnect() } }
            }
        }
        .navigationTitle("Nearby")
    }
}
