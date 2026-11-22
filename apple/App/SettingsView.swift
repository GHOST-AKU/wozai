import SwiftUI
import WozaiCore

enum AppAccent: String, CaseIterable, Identifiable {
    case system, purple, blue, teal, orange
    var id: Self { self }
    var title: LocalizedStringKey {
        switch self {
        case .system: return "System"
        case .purple: return "Purple"
        case .blue: return "Blue"
        case .teal: return "Teal"
        case .orange: return "Orange"
        }
    }
    var color: Color {
        switch self {
        case .system: return .accentColor
        case .purple: return .purple
        case .blue: return .blue
        case .teal: return .teal
        case .orange: return .orange
        }
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
                            Image(systemName: "circle.fill").foregroundStyle(swatch.color)
                        }.tag(swatch)
                    }
                }
            }
            if let identity = model.identity {
                Section("Device identity") {
                    Text(verbatim: identity.fingerprint).font(.caption.monospaced()).textSelection(.enabled)
                    Text("The signing identity stays in this device’s Keychain. Remembering a peer does not automatically verify its owner.").font(.caption).foregroundStyle(.secondary)
                }
            }
            ReceiveFolderSettings()
            Section("Privacy") {
                Toggle("Read receipts", isOn: Binding(get: { model.readReceiptsEnabled }, set: { value in Task { await model.setReadReceiptsEnabled(value) } })).disabled(model.readSettingSaving)
                Text("Read receipts are exchanged only when both people enable them. Turning this off stops new read receipts; previously confirmed receipts remain.").font(.caption).foregroundStyle(.secondary)
                Text("No account, cloud chat server, or telemetry.")
                Text("Messages stay on this device. Transport encryption does not encrypt the local chat database.").foregroundStyle(.secondary)
            }
            Section("About") {
                ShareLink(item: model.networkDiagnostics) { Label("Share connection diagnostics", systemImage: "square.and.arrow.up") }
                LabeledContent("NearbyIM", value: Bundle.main.object(forInfoDictionaryKey: "WOZAI_VERSION") as? String
                               ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                Text("Apple development build").foregroundStyle(.secondary)
                Link("Source code", destination: URL(string: "https://github.com/GHOST-AKU/wozai")!)
                Link("License", destination: URL(string: "https://github.com/GHOST-AKU/wozai/blob/main/LICENSE")!)
            }
        }
        .formStyle(.grouped)
        #if os(iOS)
        .modifier(PrivateRequestInset())
        #endif
        .navigationTitle("Settings")
        .onAppear { name = model.nickname }
    }
}
