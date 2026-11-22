import SwiftUI
import WozaiCore

@MainActor
struct NearbyView: View {
    @Environment(AppModel.self) private var model
    #if os(iOS)
    private enum Transport: Hashable { case lan, bluetooth }
    @State private var transport = Transport.lan
    @State private var showingNetworkDetails = false
    @State private var showingAddress = false
    #endif

    var body: some View {
        List {
            #if os(iOS)
            Section {
                Picker("Transport", selection: $transport) {
                    Text("Local network").tag(Transport.lan)
                    Text("Bluetooth").tag(Transport.bluetooth)
                }.pickerStyle(.segmented)
            }
            if transport == .lan {
                lanReception
                lanResults
                Section {
                    Button("Connect by address", systemImage: "network") { showingAddress = true }
                        .accessibilityIdentifier("manualAddress")
                    if !model.addresses.isEmpty || model.networkError != nil {
                        Button("Network details", systemImage: "info.circle") { showingNetworkDetails = true }
                    }
                }
            } else {
                bluetoothReception
                bluetoothResults
            }
            #else
            lanReception
            lanResults
            bluetoothReception
            bluetoothResults
            networkDetails
            Section("Connect by address") { ManualConnectionFields() }
            #endif
            if model.connectionState == .connecting {
                Section {
                    ProgressView("Connecting…")
                    Button("Cancel", role: .cancel) { model.disconnect() }
                }
            } else if model.connectionState == .connected, let peer = model.activePeer {
                Section {
                    LabeledContent("Connected device") { Text(verbatim: peer.nickname) }
                    Button("Disconnect", role: .destructive) { model.disconnect() }
                }
            } else if model.connectionState == .idle, let state = model.privateStatus {
                Section { Text(verbatim: state).foregroundStyle(.secondary) }
            }
        }
        #if os(iOS)
        .modifier(PrivateRequestInset())
        .navigationTitle("Private devices")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showingAddress) {
            Form { ManualConnectionFields() }
                .modifier(PrivateRequestInset())
                .navigationTitle("Connect by address")
                .navigationBarTitleDisplayMode(.inline)
        }
        .navigationDestination(isPresented: $showingNetworkDetails) {
            List { networkDetails }
                .modifier(PrivateRequestInset())
                .navigationTitle("Network details")
        }
        #else
        .navigationTitle("Nearby")
        #endif
    }

    private var lanReception: some View {
        Section {
            Toggle("Automatic reception", isOn: Binding(get: { model.discoveryEnabled }, set: { model.setDiscovery($0) }))
        } footer: {
            Text("Find devices on the local network. Reception resumes when you reopen the app.")
        }
    }

    @ViewBuilder private var lanResults: some View {
        if model.discoveryEnabled {
            Section {
                if model.networkError != nil {
                    Label("Check local network access", systemImage: "exclamationmark.circle").foregroundStyle(.secondary)
                    Button("Try again") { model.reconnectDiscovery() }
                } else if model.nearby.isEmpty {
                    HStack { ProgressView().controlSize(.small); Text("Looking for devices…").foregroundStyle(.secondary) }
                }
                ForEach(model.nearby) { peer in
                    Button { model.connect(peer) } label: {
                        Label(peer.nickname, systemImage: "desktopcomputer.and.iphone")
                    }.disabled(model.busy)
                }
            } header: { Text("Nearby devices") } footer: {
                if !model.nearby.isEmpty { Text("Choose a device to start a private chat. Your device remembers it after connecting.") }
            }
        }
    }

    private var bluetoothReception: some View {
        Section {
            Toggle("Discover and receive over Bluetooth", isOn: Binding(get: { model.bluetoothEnabled }, set: { model.setBluetooth($0) }))
        } footer: {
            Text("Keep both apps open. Windows connects from its Bluetooth LE device list. New identities still require confirmation.")
        }
    }

    @ViewBuilder private var bluetoothResults: some View {
        if model.bluetoothEnabled {
            Section {
                if model.bluetoothStatus == "permission" { Text("Allow Bluetooth access in Settings.") }
                else if model.bluetoothStatus == "unavailable" { Text("Bluetooth is unavailable or turned off.") }
                else if model.bluetoothStatus.hasSuffix("failed") { Text("Bluetooth connection failed. Keep both apps open and try again.") }
                else if model.bluetoothNearby.isEmpty { Text("Find this iPhone from Windows, or choose a nearby Apple device.").foregroundStyle(.secondary) }
                ForEach(model.bluetoothNearby) { peer in
                    Button { model.connect(peer) } label: {
                        Label(peer.name, systemImage: "antenna.radiowaves.left.and.right")
                    }.disabled(model.busy)
                }
            } header: { Text("Nearby devices") } footer: {
                if !model.bluetoothNearby.isEmpty { Text("Choose a device to start a private chat. Your device remembers it after connecting.") }
            }
        }
    }

    @ViewBuilder private var networkDetails: some View {
        if !model.addresses.isEmpty {
            Section("Your addresses") {
                ForEach(model.addresses, id: \.self) { Text(verbatim: $0).font(.callout.monospaced()).textSelection(.enabled) }
            }
        }
        if let error = model.networkError {
            Section {
                Label("Check local network access", systemImage: "exclamationmark.triangle")
                Text("Allow NearbyIM to access the local network in System Settings, and check that devices can reach each other.")
                Text(verbatim: error).font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
            }
        }
    }
}

@MainActor
private struct ManualConnectionFields: View {
    @Environment(AppModel.self) private var model
    @State private var host = ""
    @State private var port = ""
    private enum AddressField: Hashable { case host, port }
    @FocusState private var focus: AddressField?

    var body: some View {
        TextField("IP address or .local name", text: $host)
            .autocorrectionDisabled()
            .focused($focus, equals: .host)
            #if os(iOS)
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
            #endif
            .accessibilityIdentifier("manualHost")
        TextField("Port", text: $port)
            .focused($focus, equals: .port)
            #if os(iOS)
            .keyboardType(.numberPad)
            #endif
            .accessibilityIdentifier("manualPort")
        Button("Connect") {
            focus = nil
            model.connect(host: host, port: port)
        }
        .disabled(model.busy || host.isEmpty || port.isEmpty)
        .accessibilityIdentifier("manualConnect")
        .onDisappear { focus = nil }
    }
}
