import SwiftUI
import UniformTypeIdentifiers

private struct MeshReport: FileDocument {
    static var readableContentTypes:[UTType] { [.plainText] }
    var text:String
    init(text:String) { self.text=text }
    init(configuration:ReadConfiguration)throws { text=String(data:configuration.file.regularFileContents ?? Data(),encoding:.utf8) ?? "" }
    func fileWrapper(configuration:WriteConfiguration)throws->FileWrapper { FileWrapper(regularFileWithContents:Data(text.utf8)) }
}
@MainActor
struct PublicMeshView:View {
    @Environment(PublicMeshModel.self) private var model
    @State private var exporting=false
    @State private var report=MeshReport(text:"")
    @State private var exportFailed=false
    var body:some View {
        @Bindable var model=model
        VStack(spacing:0) {
            Form {
                Section {
                    Text(verbatim:model.status)
                    LabeledContent("Direct Mesh neighbors") { Text(verbatim:String(model.neighbors.count)) }
                    Button(model.running || model.starting ? String(localized:"Stop Mesh") : String(localized:"Start Mesh")) {
                        if model.running || model.starting { model.stop() } else { model.start() }
                    }
                    Toggle("Relay public messages",isOn:Binding(get:{model.relay},set:{model.setRelay($0)})).disabled(!model.running)
                } footer: { Text("Public messages are readable by nearby nodes. Relay is optional. Keep the app open for this experimental Bluetooth mode.") }
            }.formStyle(.grouped).frame(maxHeight:260)
            List(model.messages) { message in
                VStack(alignment:.leading,spacing:6) {
                    Text(message.own ? String(localized:"You") : String(localized:"Public Mesh")).font(.caption).foregroundStyle(.secondary)
                    Text(verbatim:message.body).textSelection(.enabled)
                    if !message.own { LabeledContent("Verified route hops") { Text(verbatim:String(message.hops)) }.font(.caption).foregroundStyle(.secondary) }
                    else { Text(message.state == "handed" ? String(localized:"Handed to a neighbor; delivery unconfirmed") : message.state == "queued" ? String(localized:"Waiting for a neighbor") : String(localized:"Expired or stopped; delivery unconfirmed")).font(.caption).foregroundStyle(.secondary) }
                }.padding(.vertical,4)
            }
            HStack {
                TextField("Public message",text:$model.draft,axis:.vertical).lineLimit(1...4).onSubmit { model.send() }
                Button("Send") { model.send() }.disabled(!model.canSend)
            }.padding()
            if model.draft.utf8.count>2048 { Text("Message is too long. Shorten it before sending.").font(.caption).foregroundStyle(.red).padding(.horizontal) }
        }
        .navigationTitle("Public Mesh")
        .toolbar { Button("Export diagnostics") { report=MeshReport(text:model.diagnostics());exporting=true } }
        .fileExporter(isPresented:$exporting,document:report,contentType:.plainText,defaultFilename:"nearby-mesh-\(Int(Date().timeIntervalSince1970)).txt") { result in
            if case .failure(let error)=result,(error as NSError).code != NSUserCancelledError { exportFailed=true }
        }
        .alert("Couldn’t export diagnostics",isPresented:$exportFailed) { Button("OK",role:.cancel) { } }
    }
}
