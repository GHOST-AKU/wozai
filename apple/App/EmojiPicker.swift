import Compression
import SwiftUI

private struct EmojiEntry: Identifiable, Sendable {
    let id: String
    let base: String
    let name: String
    let group: String
    let keywords: String
}

private struct EmojiCatalog: Sendable {
    let entries: [EmojiEntry]
    let variants: [String: [EmojiEntry]]

    static func read(url: URL, language: String) -> Self? {
        guard let archive = try? Data(contentsOf: url), archive.count > 18,
              archive.starts(with: [31, 139, 8, 0]) else { return nil }
        let uncompressedSize = archive.suffix(4).enumerated().reduce(0) { $0 | (Int($1.element) << (8 * $1.offset)) }
        guard uncompressedSize > 0, uncompressedSize <= 2 * 1024 * 1024 else { return nil }
        var text = Data(count: uncompressedSize)
        let actualSize = text.withUnsafeMutableBytes { decoded in
            archive.withUnsafeBytes { compressed in
                compression_decode_buffer(decoded.bindMemory(to: UInt8.self).baseAddress!, uncompressedSize,
                    compressed.bindMemory(to: UInt8.self).baseAddress!.advanced(by: 10), archive.count - 18, nil, COMPRESSION_ZLIB)
            }
        }
        guard actualSize == uncompressedSize, let table = String(data: text, encoding: .utf8) else { return nil }
        let language = language.lowercased()
        let column = language.hasPrefix("zh-hant") || language.hasPrefix("zh-tw") || language.hasPrefix("zh-hk") ? 8
            : language.hasPrefix("zh") ? 6 : language.hasPrefix("ja") ? 10 : language.hasPrefix("ko") ? 12 : 4
        let all = table.split(separator: "\n").compactMap { line -> EmojiEntry? in
            let fields = line.split(separator: "\t", omittingEmptySubsequences: false)
            guard fields.count == 14 else { return nil }
            let name = fields[column].isEmpty ? fields[4] : fields[column]
            return EmojiEntry(id: String(fields[0]), base: String(fields[3]), name: String(name),
                              group: String(fields[1]), keywords: [fields[4], fields[5], fields[column + 1]].joined(separator: " "))
        }
        return EmojiCatalog(entries: all.filter { $0.id == $0.base },
                            variants: Dictionary(grouping: all.filter { $0.id != $0.base }, by: \.base))
    }
}

@MainActor
struct EmojiPicker: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("recentEmoji") private var storedRecents = ""
    @State private var catalog: EmojiCatalog?
    @State private var unavailable = false
    @State private var search = ""
    @State private var group: String?
    let insert: (String) -> Void

    private let groups: [(String, LocalizedStringKey)] = [
        ("Smileys & Emotion", "Smileys"), ("People & Body", "People"), ("Animals & Nature", "Nature"),
        ("Food & Drink", "Food and drink"), ("Travel & Places", "Travel"), ("Activities", "Activities"),
        ("Objects", "Objects"), ("Symbols", "Symbols"), ("Flags", "Flags"),
    ]

    private var displayed: [EmojiEntry] {
        guard let catalog else { return [] }
        if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return catalog.entries.filter {
                $0.name.localizedCaseInsensitiveContains(search) || $0.keywords.localizedCaseInsensitiveContains(search)
                    || $0.id.contains(search)
            }
        }
        if let group { return catalog.entries.filter { $0.group == group } }
        return catalog.entries
    }

    var body: some View {
        NavigationStack {
            Group {
                if let catalog {
                    ScrollView {
                        LazyVGrid(columns: [GridItem(.adaptive(minimum: 44), spacing: 6)], spacing: 6) {
                            if group == nil && search.isEmpty && !storedRecents.isEmpty {
                                Section {
                                    ForEach(storedRecents.split(separator: "|"), id: \.self) { glyph in
                                        let value = String(glyph)
                                        Button { select(value) } label: { glyphLabel(value) }.buttonStyle(.plain)
                                    }
                                } header: {
                                    Text("Recent emoji").font(.footnote).foregroundStyle(.secondary)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                }
                            }
                            ForEach(displayed) { entry in
                                Button { select(entry.id) } label: { glyphLabel(entry.id) }
                                    .buttonStyle(.plain)
                                    .accessibilityLabel(Text(verbatim: entry.name))
                                    .contextMenu {
                                        Section {
                                            variantAction(entry)
                                            ForEach(catalog.variants[entry.id, default: []]) { variantAction($0) }
                                        } header: { Text(verbatim: entry.name) }
                                    }
                            }
                        }
                        .padding()
                    }
                    .overlay {
                        if displayed.isEmpty { ContentUnavailableView.search(text: search) }
                    }
                    .searchable(text: $search, prompt: "Search emoji")
                } else if unavailable {
                    ContentUnavailableView("Emoji are unavailable", systemImage: "face.smiling")
                } else { ProgressView() }
            }
            .navigationTitle("Emoji")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("All emoji") { group = nil }
                        ForEach(groups.indices, id: \.self) { index in
                            Button(groups[index].1) { group = groups[index].0 }
                        }
                    } label: { Label("Emoji categories", systemImage: "line.3.horizontal.decrease.circle") }
                }
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        .task {
            guard catalog == nil, let url = Bundle.main.url(forResource: "catalog.tsv", withExtension: "gz") else {
                unavailable = catalog == nil
                return
            }
            let language = Bundle.main.preferredLocalizations.first ?? "en"
            let result = await Task.detached(priority: .userInitiated) { EmojiCatalog.read(url: url, language: language) }.value
            guard !Task.isCancelled else { return }
            catalog = result
            unavailable = result == nil
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 390)
        #endif
    }

    private func glyphLabel(_ glyph: String) -> some View {
        Text(verbatim: glyph).font(.title2).frame(minWidth: 44, minHeight: 44)
            .contentShape(Rectangle())
    }

    private func variantAction(_ entry: EmojiEntry) -> some View {
        Button { select(entry.id) } label: { Text(verbatim: entry.id + "  " + entry.name) }
    }

    private func select(_ glyph: String) {
        let recent = storedRecents.split(separator: "|").map(String.init).filter { $0 != glyph }
        storedRecents = ([glyph] + Array(recent.prefix(23))).joined(separator: "|")
        insert(glyph)
    }
}
