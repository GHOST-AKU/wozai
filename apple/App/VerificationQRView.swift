import ImageIO
import SwiftUI
import UniformTypeIdentifiers
import WozaiCore
#if os(iOS)
import AVFoundation
import VisionKit
#endif

struct VerificationPNG: FileDocument {
    static var readableContentTypes: [UTType] { [.png] }
    let data: Data
    init(_ data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else { throw WozaiError.storage }
        self.data = data
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}

@MainActor
struct VerificationQRControls: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let peer: VerifiedPeer
    let generation: UUID
    @State private var png = Data()
    @State private var image: CGImage?
    @State private var exporting = false
    @State private var choosing = false
    @State private var reading = false
    @State private var matched: Bool?
    #if os(iOS)
    @State private var camera = false
    #endif
    private var current: Bool { model.activePeer == peer && model.verificationGeneration == generation && model.connectionState == .connected }

    var body: some View {
        Section("Verification QR code") {
            if let image {
                Image(decorative: image, scale: 1).interpolation(.none).resizable().scaledToFit()
                    .frame(maxWidth: 280, maxHeight: 280).background(.white)
                    .accessibilityLabel("Full identity safety code QR")
            }
            Button("Save QR image", systemImage: "square.and.arrow.down") { exporting = true }.disabled(png.isEmpty)
            Button("Read QR image", systemImage: "photo") { choosing = true }.disabled(reading || !current)
            #if os(iOS)
            if DataScannerViewController.isSupported {
                Button("Scan QR code", systemImage: "qrcode.viewfinder", action: requestCamera).disabled(!current)
            }
            #endif
            if reading { ProgressView() }
            if let matched {
                Label(matched ? LocalizedStringKey("Scanned codes match") : LocalizedStringKey("The scanned code does not match"), systemImage: matched ? "checkmark.shield" : "exclamationmark.shield")
                if matched {
                    Button("Verify scanned code") { Task { await model.verify(peer, generation: generation); dismiss() } }.disabled(!current)
                }
            }
        }
        .task(id: generation) {
            guard let identity = model.identity else { return }
            do {
                let data = try await Task.detached { try VerificationQR.png(local: identity.root, remote: peer.root) }.value
                guard !Task.isCancelled else { return }
                png = data
                if let source = CGImageSourceCreateWithData(data as CFData, nil) { image = CGImageSourceCreateImageAtIndex(source, 0, nil) }
            } catch { model.show(error) }
        }
        .fileExporter(isPresented: $exporting, document: VerificationPNG(png), contentType: .png, defaultFilename: "NearbyIM-identity-verification") { result in
            if case .failure(let error) = result { model.show(error) }
        }
        .fileImporter(isPresented: $choosing, allowedContentTypes: [.image]) { result in
            switch result {
            case .failure(let error): model.show(error)
            case .success(let url):
                reading = true; matched = nil
                Task {
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() }; reading = false }
                    do { check(try await Task.detached { try VerificationQR.read(url) }.value) }
                    catch { model.show(error) }
                }
            }
        }
        #if os(iOS)
        .sheet(isPresented: $camera) {
            NavigationStack {
                NativeQRScanner { value in check([value]); camera = false } failed: { error in
                    camera = false; model.notice = AppModel.Notice(message: error)
                }
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("Scan QR code")
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { camera = false } } }
            }
        }
        #endif
    }
    private func check(_ values: [String]) {
        guard current, let identity = model.identity else { matched = false; return }
        matched = values.count == 1 && (try? VerificationQR.matches(values[0], local: identity.root, remote: peer.root)) == true
    }
    #if os(iOS)
    private func requestCamera() {
        Task {
            let allowed = await AVCaptureDevice.requestAccess(for: .video)
            guard allowed, DataScannerViewController.isAvailable else {
                model.notice = AppModel.Notice(message: String(localized: "Camera unavailable. Read an exported QR image or compare the full code.")); return
            }
            guard current else { return }; camera = true
        }
    }
    #endif
}

#if os(iOS)
@MainActor
private struct NativeQRScanner: UIViewControllerRepresentable {
    let found: (String) -> Void
    let failed: (String) -> Void
    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let found: (String) -> Void
        init(_ found: @escaping (String) -> Void) { self.found = found }
        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue, value.utf8.count <= 128 { found(value); return }
            }
        }
    }
    func makeCoordinator() -> Coordinator { Coordinator(found) }
    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
            recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true, isGuidanceEnabled: true, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        do { try scanner.startScanning() } catch { DispatchQueue.main.async { failed(error.localizedDescription) } }
        return scanner
    }
    func updateUIViewController(_ controller: DataScannerViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: DataScannerViewController, coordinator: Coordinator) { controller.stopScanning() }
}
#endif
