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
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 12) { networkStatus; Spacer(minLength: 8); receptionButton }
                VStack(alignment: .leading, spacing: 8) { networkStatus; receptionButton }
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
        .navigationTitle("Nearby")
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
                Button("Nearby options", systemImage: "ellipsis.circle") { options = true }
            }
        }
        .confirmationDialog("Start public reception?", isPresented: $consent, titleVisibility: .visible) {
            Button("Start reception") { model.enableReception() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Nearby devices and relays can read these messages. Public messages are not end-to-end encrypted. Reception resumes when you reopen the app. The latest 200 messages stay only until the app quits.")
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
                    Section("Public messages") {
                        Text("Nearby devices and relays can read your messages. A device passing on a message does not confirm who received it.")
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
                .navigationTitle("Nearby options")
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

    private var networkStatus: some View {
        VStack(alignment: .leading, spacing: 4) {
            if model.starting {
                ProgressView("Starting reception…")
            } else if model.neighbors.isEmpty || model.sendFailed {
                Text(verbatim: model.status)
            } else {
                Label("Direct connections: \(model.neighbors.count)", systemImage: "dot.radiowaves.left.and.right")
            }
            Text("Public messages · No end-to-end encryption")
                .font(.caption).foregroundStyle(.secondary)
        }
        .font(.subheadline)
    }

    @ViewBuilder private var receptionButton: some View {
        if model.receptionEnabled {
            Button("Stop public reception") { model.stop() }
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
                    .accessibilityHint(model.running ? Text("Composes a public message") : Text("Start public reception before sending."))
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
                    Group {
                        switch message.state {
                        case "handed": Text("Passed to a nearby device · Delivery unconfirmed")
                        case "queued": Text("Waiting for a nearby device")
                        case "stopped": Text("Reception stopped · Delivery unconfirmed")
                        default: Text("Expired · Delivery unconfirmed")
                        }
                    }.font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(message.own ? accent.opacity(0.14) : Color.secondary.opacity(0.1),
                        in: RoundedRectangle(cornerRadius: 16))
            if !message.own { Spacer(minLength: 40) }
        }
    }
}
