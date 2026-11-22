import SwiftUI
import UniformTypeIdentifiers

private struct MeshReport: FileDocument {
    static var readableContentTypes: [UTType] { [.plainText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(data: configuration.file.regularFileContents ?? Data(), encoding: .utf8) ?? ""
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

@MainActor
struct PublicMeshView: View {
    @Environment(PublicMeshModel.self) private var model
    @Environment(\.wozaiAccent) private var accent
    @State private var consent = false
    @State private var options = false
    @State private var exporting = false
    @State private var report = MeshReport(text: "")
    @State private var exportFailed = false
    var onPrivate: (() -> Void)?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    if !model.neighbors.isEmpty {
                        Label("Nearby devices connected", systemImage: "dot.radiowaves.left.and.right")
                    } else if model.starting {
                        Text("Starting reception…")
                    } else {
                        Text(verbatim: model.status)
                    }
                    Text(model.relay ? "Public relay enabled" : "Public messages")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .font(.subheadline)
                Spacer(minLength: 8)
                receptionButton
            }
            .frame(maxWidth: 760)
            .padding(.horizontal, 16).padding(.vertical, 8)
            Divider()
            if model.messages.isEmpty {
                ContentUnavailableView("No public messages yet", systemImage: "bubble.left.and.bubble.right",
                                       description: Text("Nearby devices can read messages sent here."))
                    .frame(maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(model.messages) { message in
                                publicMessage(message).id(message.id)
                            }
                        }
                        .frame(maxWidth: 760)
                        .padding(16)
                        .frame(maxWidth: .infinity)
                    }
                    .scrollDismissesKeyboard(.interactively)
                    .onChange(of: model.messages.last?.id, initial: true) { _, id in
                        if let id { proxy.scrollTo(id, anchor: .bottom) }
                    }
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { composer }
        .navigationTitle("Public chat")
        #if os(iOS)
        .modifier(PrivateRequestInset())
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if let onPrivate {
                ToolbarItem(placement: .primaryAction) {
                    Button("Private devices", systemImage: "person.2", action: onPrivate)
                        .accessibilityIdentifier("privateDevices")
                }
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("Public chat options", systemImage: "ellipsis.circle") { options = true }
            }
        }
        .confirmationDialog("Start public reception?", isPresented: $consent, titleVisibility: .visible) {
            Button("Start reception") { model.enableReception() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Nearby devices can read these messages. Reception resumes when you reopen the app. The latest 200 messages stay only until the app quits.")
        }
        .sheet(isPresented: $options) {
            NavigationStack {
                Form {
                    Section {
                        Toggle("Relay public messages", isOn: Binding(get: { model.relay }, set: { model.setRelay($0) }))
                            .disabled(!model.running)
                    } footer: { Text("Relay passes public messages between nearby devices. It starts off every time.") }
                    Section("Temporary history") {
                        Text("The latest 200 public messages are kept in this app session. Stopping reception preserves them until the app quits.")
                    }
                    Section {
                        LabeledContent("Transport", value: "Bluetooth")
                        LabeledContent("Direct Mesh neighbors", value: String(model.neighbors.count))
                        Button("Export diagnostics", systemImage: "square.and.arrow.up") {
                            report = MeshReport(text: model.diagnostics())
                            options = false
                            exporting = true
                        }
                    }
                }
                .navigationTitle("Public chat options")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { options = false } } }
            }
            #if os(macOS)
            .frame(minWidth: 440, minHeight: 360)
            #endif
        }
        .fileExporter(isPresented: $exporting, document: report, contentType: .plainText,
                      defaultFilename: "nearby-mesh-\(Int(Date().timeIntervalSince1970)).txt") { result in
            if case .failure(let error) = result, (error as NSError).code != NSUserCancelledError { exportFailed = true }
        }
        .alert("Couldn’t export diagnostics", isPresented: $exportFailed) {
            Button("OK", role: .cancel) {}
        }
    }

    @ViewBuilder private var receptionButton: some View {
        if model.receptionEnabled {
            Button("Stop reception") { model.stop() }
        } else {
            Button("Start reception") { consent = true }
                .buttonStyle(.bordered).disabled(!model.coreAvailable)
        }
    }

    private var composer: some View {
        @Bindable var model = model
        return VStack(alignment: .leading, spacing: 4) {
            if model.draft.utf8.count > 2048 {
                Text("Message is too long. Shorten it before sending.")
                    .font(.caption).foregroundStyle(.red)
            }
            HStack(spacing: 8) {
                TextField("Public message", text: $model.draft, axis: .vertical)
                    .lineLimit(1...5).textFieldStyle(.plain)
                    .padding(.horizontal, 14).padding(.vertical, 10)
                    .frame(minHeight: 44)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22))
                    .overlay { RoundedRectangle(cornerRadius: 22).strokeBorder(.quaternary) }
                Button { model.send() } label: {
                    Label("Send", systemImage: "arrow.up.circle.fill").labelStyle(.iconOnly).font(.system(size: 30))
                        .frame(width: 44, height: 44).contentShape(Rectangle())
                }.buttonStyle(.borderless).disabled(!model.canSend)
            }
        }
        .frame(maxWidth: 760)
        .padding(.horizontal, 12).padding(.vertical, 6)
        .frame(maxWidth: .infinity)
        .background(.bar)
    }

    private func publicMessage(_ message: PublicMeshModel.Message) -> some View {
        HStack {
            if message.own { Spacer(minLength: 40) }
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: message.body).textSelection(.enabled)
                if message.own {
                    Text(message.state == "handed" ? "Handed to a neighbor; delivery unconfirmed"
                         : message.state == "queued" ? "Waiting for a neighbor" : "Expired or stopped; delivery unconfirmed")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(message.own ? accent.opacity(0.14) : Color.secondary.opacity(0.1),
                        in: RoundedRectangle(cornerRadius: 16))
            if !message.own { Spacer(minLength: 40) }
        }
    }
}
