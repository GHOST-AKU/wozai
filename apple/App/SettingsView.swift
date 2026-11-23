import SwiftUI
import WozaiCore

enum AppAccent: String, CaseIterable, Identifiable {
    case system, purple, blue, teal, orange
    var id: Self { self }
    var title: LocalizedStringKey {
        switch self {
        case .system: return "System accent"
        case .purple: return "Purple"
        case .blue: return "Blue"
        case .teal: return "Teal"
        case .orange: return "Orange"
        }
    }
    // Preset tint roles match Android Next. System accent follows the native platform.
    func color(_ scheme: ColorScheme) -> Color {
        let rgb: UInt32
        switch self {
        case .system: return .accentColor
        case .purple: rgb = scheme == .dark ? 0xD8BFFF : 0x624398
        case .blue: rgb = scheme == .dark ? 0xAAC9FF : 0x185BAA
        case .teal: rgb = scheme == .dark ? 0x85D6C8 : 0x006B61
        case .orange: rgb = scheme == .dark ? 0xFFB783 : 0x984500
        }
        return Color(red: Double((rgb >> 16) & 255) / 255, green: Double((rgb >> 8) & 255) / 255,
                     blue: Double(rgb & 255) / 255)
    }
}

private struct WozaiAccentKey: EnvironmentKey { static let defaultValue = Color.accentColor }
extension EnvironmentValues {
    var wozaiAccent: Color {
        get { self[WozaiAccentKey.self] }
        set { self[WozaiAccentKey.self] = newValue }
    }
}

@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage("appearance") private var appearance = "system"
    @AppStorage("accent") private var accent = AppAccent.purple
    @State private var name = ""

    var body: some View {
        Form {
            Section("This device") {
                TextField("Device name", text: $name).disabled(model.busy)
                Button("Save name") { model.updateNickname(name) }.disabled(model.busy || name == model.nickname)
                if model.busy { Text("Disconnect before changing the device name.").font(.caption).foregroundStyle(.secondary) }
            }
            Section("Appearance") {
                Picker("Theme", selection: $appearance) {
                    Text("System").tag("system")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
                Picker("Accent color", selection: $accent) {
                    ForEach(AppAccent.allCases) { swatch in
                        Label { Text(swatch.title) } icon: {
                            Image(systemName: "circle.fill").foregroundStyle(swatch.color(colorScheme))
                        }.tag(swatch)
                    }
                }
            }
            ReceiveFolderSettings()
            Section {
                Toggle("Read receipts", isOn: Binding(get: { model.readReceiptsEnabled }, set: { value in Task { await model.setReadReceiptsEnabled(value) } })).disabled(model.readSettingSaving)
                NavigationLink("Privacy and security") {
                    Form {
                        Section("Read receipts") {
                            Text("Read receipts are exchanged only when both people enable them. Turning this off stops new read receipts; previously confirmed receipts remain.")
                        }
                        Section("Privacy") {
                            Text("No account, cloud chat server, or telemetry.")
                            Text("Messages stay on this device. Transport encryption does not encrypt the local chat database.")
                        }
                    }
                    .navigationTitle("Privacy and security")
                }
            } header: {
                Text("Privacy")
            } footer: {
                Text("Read receipts require both devices to opt in.")
            }
            if let identity = model.identity {
                Section {
                    NavigationLink {
                        Form {
                            Section("Device identity") {
                                let fingerprint = identity.fingerprint
                                Text(verbatim: fingerprint).font(.callout.monospaced()).textSelection(.enabled)
                                ShareLink(item: fingerprint) { Label("Share device fingerprint", systemImage: "square.and.arrow.up") }
                                Text("The signing identity stays in this device’s Keychain. Remembering a peer does not automatically verify its owner.")
                                    .font(.footnote).foregroundStyle(.secondary)
                            }
                        }
                        .navigationTitle("Device identity")
                    } label: {
                        LabeledContent("Device identity") { Text(String(identity.fingerprint.prefix(12))).font(.caption.monospaced()) }
                    }
                }
            }
            Section("About") {
                NavigationLink {
                    Form {
                        LabeledContent("NearbyIM", value: Bundle.main.object(forInfoDictionaryKey: "WOZAI_VERSION") as? String
                                       ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                        Link("Source code", destination: URL(string: "https://github.com/GHOST-AKU/wozai")!)
                        Link("License", destination: URL(string: "https://github.com/GHOST-AKU/wozai/blob/main/LICENSE")!)
                        ShareLink(item: model.networkDiagnostics) { Label("Share connection diagnostics", systemImage: "square.and.arrow.up") }
                    }.navigationTitle("About")
                } label: { Label("About", systemImage: "info.circle") }
                .accessibilityIdentifier("settingsAbout")
            }
        }
        .formStyle(.grouped)
        #if os(iOS)
        .modifier(PrivateRequestInset())
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .navigationTitle("Settings")
        .onAppear { name = model.nickname }
    }
}
