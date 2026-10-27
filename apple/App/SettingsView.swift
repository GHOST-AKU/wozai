import SwiftUI
import WozaiCore

@MainActor
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @AppStorage("appearance") private var appearance = "system"
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
            }
            if let identity = model.identity {
                Section("Device identity") {
                    Text(verbatim: identity.fingerprint).font(.caption.monospaced()).textSelection(.enabled)
                    Text("The signing identity stays in this device’s Keychain. Remembering a peer does not automatically verify its owner.").font(.caption).foregroundStyle(.secondary)
                }
            }
            ReceiveFolderSettings()
            Section("Privacy") {
                Text("No account, cloud chat server, or telemetry.")
                Text("Messages stay on this device. Transport encryption does not encrypt the local chat database.").foregroundStyle(.secondary)
            }
            Section("About") {
                LabeledContent("NearbyIM", value: Bundle.main.object(forInfoDictionaryKey: "WOZAI_VERSION") as? String
                               ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                Text("Apple development build").foregroundStyle(.secondary)
                Link("Source code", destination: URL(string: "https://github.com/GHOST-AKU/wozai")!)
                Link("License", destination: URL(string: "https://github.com/GHOST-AKU/wozai/blob/main/LICENSE")!)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        .onAppear { name = model.nickname }
    }
}
