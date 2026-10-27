import ImageIO
import PhotosUI
import QuickLook
import SwiftUI
import UniformTypeIdentifiers
import WozaiCore
#if os(macOS)
import AppKit
#else
import UIKit
#endif

@MainActor
struct AttachmentControls: View {
    @Environment(AppModel.self) private var model
    @State private var choosingFiles = false
    @State private var photos: [PhotosPickerItem] = []

    var body: some View {
        Menu {
            Button("Choose files", systemImage: "doc.badge.plus") { choosingFiles = true }
            PhotosPicker(selection: $photos, maxSelectionCount: 256, matching: .any(of: [.images, .videos]), preferredItemEncoding: .current) {
                Label("Photos and videos", systemImage: "photo.on.rectangle")
            }
        } label: { Label("Attach", systemImage: "plus.circle").labelStyle(.iconOnly) }
        .disabled(model.connectionState != .connected)
        .fileImporter(isPresented: $choosingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            switch result {
            case .success(let urls): model.sendFiles(urls)
            case .failure(let error): model.fileError(error)
            }
        }
        .onChange(of: photos) { _, selected in
            guard !selected.isEmpty else { return }
            Task {
                do {
                    var urls: [URL] = []
                    for photo in selected {
                        if let imported = try await photo.loadTransferable(type: ImportedMedia.self) { urls.append(imported.url) }
                    }
                    model.sendFiles(urls); photos = []
                } catch { model.fileError(error) }
            }
        }
    }
}

private struct ImportedMedia: Transferable {
    let url: URL
    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .item) { incoming in
            let folder = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Selected media", isDirectory: true)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let destination = folder.appendingPathComponent(UUID().uuidString + "-" + incoming.file.lastPathComponent)
            // Preserve original bytes through a filesystem copy, without loading a movie into memory.
            try FileManager.default.copyItem(at: incoming.file, to: destination)
            return ImportedMedia(url: destination)
        }
    }
}

@MainActor
struct AttachmentTimeline: View {
    @Environment(AppModel.self) private var model
    let contact: Conversation
    var body: some View {
        ForEach(Array(model.transfers.values.filter { $0.peerID == contact.id && $0.root == contact.root }
            .sorted { $0.info.time < $1.info.time }.suffix(50)), id: \.key) { record in
            AttachmentBubble(record: record)
        }
        .task(id: contact.id) { await model.loadTransfers(contact) }
    }
}

@MainActor
private struct AttachmentBubble: View {
    @Environment(AppModel.self) private var model
    let record: AttachmentRecord
    @State private var preview: URL?
    @State private var image: CGImage?
    private var complete: Bool { ["received", "saved"].contains(record.state) }
    private var file: URL { record.outgoing ? record.path : record.destination }
    private var status: String {
        switch record.state {
        case "queued": return String(localized: "Queued")
        case "offered": return String(localized: "Waiting for acceptance")
        case "paused": return String(localized: "Paused")
        case "saved", "received": return String(localized: "Saved")
        case "canceled": return String(localized: "Canceled")
        case "failed": return String(localized: "Failed")
        case "awaiting_saved": return String(localized: "Waiting for saved receipt")
        default: return String(localized: "Transferring")
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let image { Image(decorative: image, scale: 1).resizable().scaledToFit().frame(maxWidth: 320, maxHeight: 240) }
            Label { Text(verbatim: record.info.name).lineLimit(2) } icon: { Image(systemName: record.info.mime.hasPrefix("image/") ? "photo" : "doc") }
            Text(status).font(.caption)
            if !complete {
                ProgressView(value: Double(record.offset), total: Double(max(1, record.info.size)))
                Text("\(ByteCountFormatter.string(fromByteCount: Int64(record.offset), countStyle: .file)) / \(ByteCountFormatter.string(fromByteCount: Int64(record.info.size), countStyle: .file))").font(.caption.monospacedDigit())
            }
            LocalPathLabel(path: model.path(for: record))
            HStack {
                if complete {
                    Button("Open") { model.accessFile(file); preview = file }
                    ShareLink(item: file) { Label("Share", systemImage: "square.and.arrow.up") }
                    #if os(macOS)
                    Button("Show in Finder") { model.accessFile(file); NSWorkspace.shared.activateFileViewerSelecting([file]) }
                    #endif
                } else if model.connectionState == .connected {
                    if record.state == "paused" { Button("Resume") { model.fileControl(record.id, resume: true) } }
                    else if !["canceled", "failed"].contains(record.state) {
                        Button("Pause") { model.fileControl(record.id) }.disabled(record.state == "offered")
                    }
                    if !["canceled", "failed"].contains(record.state) { Button("Cancel", role: .destructive) { model.fileControl(record.id, cancel: true) } }
                }
            }.buttonStyle(.borderless)
        }
        .padding(12).background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
        .frame(maxWidth: 420, alignment: .leading)
        .quickLookPreview($preview)
        .task(id: record.state) {
            guard complete, record.info.mime.hasPrefix("image/") else { return }
            model.accessFile(file)
            image = await Task.detached(priority: .utility) { () -> CGImage? in
                guard let source = CGImageSourceCreateWithURL(file as CFURL, [kCGImageSourceShouldCache: false] as CFDictionary),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                      let width = properties[kCGImagePropertyPixelWidth] as? Int,
                      let height = properties[kCGImagePropertyPixelHeight] as? Int,
                      width > 0, height > 0, width <= 32768, height <= 32768, Int64(width) * Int64(height) <= 100_000_000 else { return nil }
                return CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 1024] as CFDictionary)
            }.value
        }
    }
}

@MainActor
struct ReceiveFolderSettings: View {
    @Environment(AppModel.self) private var model
    @State private var selecting = false
    var body: some View {
        Section("Files") {
            Button("Choose receive folder", systemImage: "folder") { selecting = true }
                .disabled(model.busy)
            Text(model.receiveFolderName).font(.caption).foregroundStyle(.secondary)
        }
        .fileImporter(isPresented: $selecting, allowedContentTypes: [.folder]) { result in
            switch result {
            case .success(let url): model.chooseReceiveFolder(url)
            case .failure(let error): model.fileError(error)
            }
        }
    }
}
