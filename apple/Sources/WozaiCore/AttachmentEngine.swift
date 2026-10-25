import CryptoKit
import Darwin
import Foundation

public struct AttachmentRecord: Codable, Sendable, Identifiable, Equatable {
    public var id: String { info.id }
    public let peerID: String
    public let root: Data
    public let outgoing: Bool
    public let info: AttachmentInfo
    public let source: String
    public var path: URL
    public let destination: URL
    public var offset: UInt64
    public var digest: Data
    public var state: String
    public var manualPause: Bool
    public let modified: Date?
    public var queued = false
    public var order: UInt64 = 0
    public var key: String { Data(SHA256.hash(data: root + Data("\(peerID):\(outgoing):\(id):\(source)".utf8))).hexString }
}

/// Owned exclusively by one ChatSession actor; disk work never runs on MainActor.
public final class AttachmentEngine {
    private final class Task {
        var record: AttachmentRecord
        let file: FileHandle
        var whole = SHA256(), block = SHA256()
        var sent: UInt64 = 0, verified: UInt64 = 0, credit: UInt64 = 0, window: UInt64 = 0
        var active = false
        init(_ record: AttachmentRecord, _ file: FileHandle) { self.record = record; self.file = file }
    }
    private struct Ended {
        let record: AttachmentRecord
        var bytes: UInt64
        var controls = 128
    }
    private let peer: VerifiedPeer
    private let connection: String
    private let journal: URL
    private let directory: URL
    private var incoming: Task?, outgoing: Task?
    private var reserved: AttachmentRecord?
    private var ended: [Ended] = []
    private var queuedCount = 0
    public private(set) var notices: [AttachmentRecord] = []
    private let window: UInt64 = 2 * AttachmentInfo.blockBytes

    public init(peer: VerifiedPeer, connection: String, journal: URL, directory: URL) throws {
        self.peer = peer; self.connection = connection; self.journal = journal; self.directory = directory
        try FileManager.default.createDirectory(at: journal, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        var excluded = journal, values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
        let stored = try records()
        queuedCount = stored.filter { $0.queued && $0.state == "queued" }.count
        reserved = stored.first { $0.outgoing && !$0.queued && !["saved", "received", "canceled", "failed"].contains($0.state) }
    }
    public var canPump: Bool {
        if outgoing == nil && reserved == nil && queuedCount > 0 { return true }
        guard let task = outgoing, task.active else { return false }
        if task.sent == task.record.info.size { return true }
        let n = min(UInt64(AttachmentInfo.chunkBytes), task.record.info.size - task.sent, AttachmentInfo.blockBytes - task.sent % AttachmentInfo.blockBytes)
        return task.sent + n <= task.credit + task.window
    }
    public func drainNotices() -> [AttachmentRecord] { let result = notices; notices.removeAll(keepingCapacity: true); return result }
    private func key(_ record: AttachmentRecord) -> String {
        Data(SHA256.hash(data: peer.root + Data("\(peer.id):\(record.outgoing):\(record.id):\(record.source)".utf8))).hexString
    }
    private func save(_ record: AttachmentRecord) throws {
        let url = journal.appendingPathComponent(key(record) + ".json")
        try JSONEncoder().encode(record).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        let file = try FileHandle(forWritingTo: url); try file.synchronize(); try file.close()
        try syncDirectory(journal)
        if ["saved", "received", "canceled", "failed"].contains(record.state) { remember(record) }
        notices.append(record)
    }
    private func remember(_ record: AttachmentRecord) {
        guard !ended.contains(where: { $0.record.key == record.key }) else { return }
        // Bound both memory and the already-sent traffic accepted after a task ends.
        ended.append(Ended(record: record, bytes: window))
        if ended.count > 32 { ended.removeFirst() }
    }
    private func discardEnded(_ p: AttachmentPacket) throws -> Bool {
        guard let index = ended.firstIndex(where: {
            $0.record.id == p.id && $0.record.source == p.source && $0.record.outgoing != p.fromSender && $0.record.info.size == p.size
        }) else { return false }
        if p.kind == .data {
            guard UInt64(p.payload.count) <= ended[index].bytes else { throw WozaiError.protocolViolation }
            ended[index].bytes -= UInt64(p.payload.count)
        } else {
            guard ended[index].controls > 0 else { throw WozaiError.protocolViolation }
            ended[index].controls -= 1
        }
        return true
    }
    public func records() throws -> [AttachmentRecord] {
        var result: [AttachmentRecord] = []
        let paths = try FileManager.default.contentsOfDirectory(at: journal, includingPropertiesForKeys: [.fileSizeKey, .isSymbolicLinkKey])
        for path in paths where path.pathExtension == "json" {
            let values = try path.resourceValues(forKeys: [.fileSizeKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true, (values.fileSize ?? Int.max) <= 16384 else { throw WozaiError.storage }
            let record = try JSONDecoder().decode(AttachmentRecord.self, from: Data(contentsOf: path))
            try record.info.validate()
            guard record.peerID == peer.id, record.root == peer.root else { continue }
            guard validID(record.source), record.offset <= record.info.size, path.lastPathComponent == key(record) + ".json" else { throw WozaiError.storage }
            result.append(record)
        }
        return result.sorted { $0.order == $1.order ? $0.info.time < $1.info.time : $0.order < $1.order }
    }
    private func lookup(_ packet: AttachmentPacket, outgoing: Bool) throws -> AttachmentRecord? {
        try records().first { $0.id == packet.id && $0.source == packet.source && $0.outgoing == outgoing && $0.info.size == packet.size }
    }
    private func packet(_ record: AttachmentRecord, _ kind: AttachmentPacket.Kind) -> AttachmentPacket {
        AttachmentPacket(kind: kind, fromSender: record.outgoing, info: record.info, source: record.source, connection: connection)
    }
    private func reject(_ offer: AttachmentPacket, reason: String) -> AttachmentPacket {
        var reply = offer.reply(.cancel); reply.reason = reason; return reply
    }
    public func offer(_ url: URL, mime: String = "application/octet-stream") throws -> (String, [AttachmentPacket]) {
        guard queuedCount < 256 else { throw WozaiError.busy }
        let path = url.standardizedFileURL.resolvingSymlinksInPath()
        let values = try path.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
        guard values.isRegularFile == true, let size = values.fileSize, size >= 0 else { throw WozaiError.storage }
        let info = try AttachmentInfo(id: UUID().uuidString.lowercased(), name: path.lastPathComponent, mime: mime,
            size: UInt64(size), time: UInt64(Date().timeIntervalSince1970 * 1000))
        var record = AttachmentRecord(peerID: peer.id, root: peer.root, outgoing: true, info: info,
            source: UUID().uuidString.lowercased(), path: path, destination: path, offset: 0,
            digest: Data(SHA256.hash(data: Data())), state: "offered", manualPause: false, modified: values.contentModificationDate)
        _ = try openFile(path, writing: false)
        record.queued = true; record.state = "queued"
        record.order = (try records().map(\.order).max() ?? 0) + 1
        try save(record); queuedCount += 1
        return (info.id, try startNext())
    }
    public func handle(_ p: AttachmentPacket) throws -> [AttachmentPacket] {
        try p.validate()
        guard p.connection == connection else { throw WozaiError.protocolViolation }
        if p.kind == .offer {
            guard incoming == nil, try lookup(p, outgoing: false) == nil else { return [reject(p, reason: "busy")] }
            let info = p.info!
            do {
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                let root = directory.standardizedFileURL.resolvingSymlinksInPath()
                let capacity = try root.resourceValues(forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity
                guard let capacity, UInt64(max(0, capacity)) > info.size + 16 * AttachmentInfo.blockBytes else { throw WozaiError.storage }
                let safeName = safeFilename(info.name)
                let destination = root.appendingPathComponent(info.id + "-" + safeName)
                let part = root.appendingPathComponent(".nearbyim-\(info.id)-\(p.source).part")
                let descriptor = Darwin.open(part.path, O_RDWR | O_CREAT | O_EXCL | O_NOFOLLOW, 0o600)
                guard descriptor >= 0 else { throw WozaiError.storage }
                let file = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
                let record = AttachmentRecord(peerID: peer.id, root: peer.root, outgoing: false, info: info, source: p.source,
                    path: part, destination: destination, offset: 0, digest: Data(SHA256.hash(data: Data())), state: "receiving", manualPause: false, modified: nil)
                try save(record)
                let task = Task(record, file); task.window = window; task.active = true; incoming = task
                var reply = p.reply(.accept); reply.window = window; return [reply]
            } catch { return [reject(p, reason: "failed")] }
        }
        if p.kind == .status {
            guard var record = try lookup(p, outgoing: !p.fromSender) else { return [reject(p, reason: "rejected")] }
            guard !["canceled", "failed"].contains(record.state) else { return [reject(p, reason: "canceled")] }
            if record.manualPause { return [p.reply(.pause)] }
            if !p.fromSender { try restoreSender(record); return [packet(record, .status)] }
            if record.state == "received" || FileManager.default.fileExists(atPath: record.destination.path) {
                guard record.offset == record.info.size else { throw WozaiError.storage }
                let final = try openFile(record.destination, writing: false)
                defer { try? final.close() }
                let size = try final.seekToEnd()
                guard size == record.info.size, Data(try hashPrefix(final, size).finalize()) == record.digest else { throw WozaiError.storage }
                record.state = "received"; try save(record)
                var reply = p.reply(.complete); reply.offset = record.info.size; reply.hash = record.digest; return [reply]
            }
            let file = try openFile(record.path, writing: true)
            let whole = try hashPrefix(file, record.offset)
            guard Data(whole.finalize()) == record.digest else { throw WozaiError.storage }
            try file.truncate(atOffset: record.offset); try file.seek(toOffset: record.offset)
            record.state = "receiving"; try save(record)
            let task = Task(record, file); task.whole = whole; task.sent = record.offset; task.verified = record.offset
            task.credit = record.offset; task.window = window; task.active = true; incoming = task
            var reply = p.reply(.resume); reply.offset = record.offset; reply.verified = record.offset
            reply.durable = record.offset; reply.window = window; reply.hash = record.digest; return [reply]
        }
        let isOutgoing = !p.fromSender
        let current = isOutgoing ? outgoing : incoming
        guard let task = current, task.record.id == p.id, task.record.source == p.source, task.record.info.size == p.size else {
            if try discardEnded(p) { return [] }
            if p.kind == .cancel, var record = try lookup(p, outgoing: isOutgoing) {
                if !["saved", "received", "canceled", "failed"].contains(record.state) {
                    if record.queued && record.state == "queued" { queuedCount = max(0, queuedCount - 1) }
                    record.state = p.reason == "failed" || p.reason == "tooLarge" ? "failed" : "canceled"
                    record.manualPause = false
                    try save(record)
                    if reserved?.key == record.key { reserved = nil }
                } else { remember(record) }
                _ = try discardEnded(p)
                return []
            }
            throw WozaiError.protocolViolation
        }
        switch p.kind {
        case .accept:
            guard isOutgoing, task.record.state == "offered", p.offset == 0, p.window > 0 else { throw WozaiError.protocolViolation }
            task.window = p.window; task.active = true; task.record.state = "sending"
        case .resume:
            guard isOutgoing, p.window > 0, p.offset == p.verified, p.offset == p.durable else { throw WozaiError.protocolViolation }
            task.whole = try hashPrefix(task.file, p.offset)
            guard Data(task.whole.finalize()) == p.hash else { throw WozaiError.storage }
            task.sent = p.offset; task.credit = p.offset; task.verified = p.offset; task.window = p.window
            task.active = true; task.record.offset = p.offset; task.record.state = "sending"; task.block = SHA256()
        case .credit:
            guard isOutgoing, p.offset >= task.credit, p.offset <= task.sent, p.verified >= task.verified, p.window > 0 else { throw WozaiError.protocolViolation }
            task.credit = p.offset; task.verified = p.verified; task.window = p.window; task.record.offset = p.durable
        case .data:
            guard task.active else { return [] }
            guard !isOutgoing, p.offset == task.sent, UInt64(p.payload.count) <= AttachmentInfo.blockBytes - (task.sent - task.verified),
                  UInt64(p.payload.count) <= window - (task.sent - task.credit) else { throw WozaiError.protocolViolation }
            try task.file.write(contentsOf: p.payload); task.whole.update(data: p.payload); task.block.update(data: p.payload)
            task.sent += UInt64(p.payload.count)
            if task.sent - task.credit >= 128 * 1024 || task.sent == p.size { return [credit(task)] }
        case .blockHash:
            guard task.active else { return [] }
            guard !isOutgoing, p.offset == task.sent, task.sent > task.verified,
                  task.sent - task.verified == AttachmentInfo.blockBytes || task.sent == p.size,
                  Data(task.block.finalize()) == p.hash else { throw WozaiError.protocolViolation }
            task.block = SHA256(); task.verified = task.sent
            try task.file.synchronize(); task.record.offset = task.verified; task.record.digest = Data(task.whole.finalize())
            try save(task.record); return [credit(task)]
        case .end:
            guard task.active else { return [] }
            guard !isOutgoing, task.sent == p.size, task.verified == p.size,
                  Data(task.whole.finalize()) == p.hash else { throw WozaiError.protocolViolation }
            try task.file.synchronize()
            task.record.offset = p.size; task.record.digest = p.hash!; task.record.state = "publishing"
            try save(task.record)
            // Exclusive rename works on same-volume removable storage without requiring hard links.
            guard Darwin.renamex_np(task.record.path.path, task.record.destination.path, UInt32(RENAME_EXCL)) == 0 else { throw WozaiError.storage }
            try syncDirectory(task.record.destination.deletingLastPathComponent())
            task.record.offset = p.size; task.record.digest = p.hash!; task.record.state = "received"
            try save(task.record); try task.file.close()
            incoming = nil
            var reply = p.reply(.complete); reply.offset = p.size; reply.hash = p.hash; return [reply]
        case .complete:
            guard isOutgoing else { throw WozaiError.protocolViolation }
            if task.record.state != "awaiting_saved" { task.whole = try hashPrefix(task.file, p.size) }
            guard Data(task.whole.finalize()) == p.hash else { throw WozaiError.protocolViolation }
            task.record.offset = p.size; task.record.digest = p.hash!; task.record.state = "saved"
            try save(task.record); try task.file.close(); outgoing = nil
        case .pause, .cancel:
            task.active = false; task.record.state = p.kind == .pause ? "paused" : p.reason == "failed" || p.reason == "tooLarge" ? "failed" : "canceled"
            if !isOutgoing {
                try task.file.truncate(atOffset: task.verified); try task.file.synchronize()
                task.record.offset = task.verified; task.whole = try hashPrefix(task.file, task.verified)
                task.record.digest = Data(task.whole.finalize())
            }
            try save(task.record)
            if task.record.state != "paused" {
                try task.file.close()
                if isOutgoing { outgoing = nil } else { incoming = nil }
            }
        default: throw WozaiError.protocolViolation
        }
        return []
    }
    private func startNext() throws -> [AttachmentPacket] {
        guard outgoing == nil, reserved == nil else { return [] }
        for var record in try records().filter({ $0.queued && $0.state == "queued" }) {
            queuedCount = max(0, queuedCount - 1)
            do {
                try verifySource(record)
                let file = try openFile(record.path, writing: false)
                record.queued = false; record.state = "offered"; try save(record)
                outgoing = Task(record, file)
                return [packet(record, .offer)]
            } catch {
                record.queued = false; record.state = "failed"; try save(record)
            }
        }
        return []
    }
    private func credit(_ task: Task) -> AttachmentPacket {
        var reply = packet(task.record, .credit); reply.offset = task.sent; reply.verified = task.verified
        reply.durable = task.record.offset; reply.window = window; task.credit = task.sent; return reply
    }
    public func pump() throws -> [AttachmentPacket] {
        let next = try startNext(); if !next.isEmpty { return next }
        guard let task = outgoing, task.active else { return [] }
        var packets: [AttachmentPacket] = []
        for _ in 0..<4 {
            guard task.sent < task.record.info.size else { break }
            let n = min(UInt64(AttachmentInfo.chunkBytes), task.record.info.size - task.sent, AttachmentInfo.blockBytes - task.sent % AttachmentInfo.blockBytes)
            guard task.sent + n <= task.credit + task.window else { break }
            let bytes = try task.file.read(upToCount: Int(n)) ?? Data()
            guard bytes.count == n else { throw WozaiError.storage }
            task.whole.update(data: bytes); task.block.update(data: bytes)
            var data = packet(task.record, .data); data.offset = task.sent; data.payload = bytes; packets.append(data)
            task.sent += n
            if task.sent % AttachmentInfo.blockBytes == 0 || task.sent == task.record.info.size {
                var block = packet(task.record, .blockHash); block.offset = task.sent; block.hash = Data(task.block.finalize())
                packets.append(block); task.block = SHA256()
            }
        }
        if task.sent == task.record.info.size {
            try verifySource(task.record)
            var end = packet(task.record, .end); end.offset = task.sent; end.hash = Data(task.whole.finalize())
            task.active = false; task.record.state = "awaiting_saved"; task.record.digest = end.hash!
            try save(task.record); packets.append(end)
        }
        var notice = task.record; notice.offset = task.sent; notices.append(notice)
        return packets
    }
    public func resume(_ id: String) throws -> [AttachmentPacket] {
        guard var record = try records().first(where: { $0.id == id }) else { throw WozaiError.storage }
        guard !["canceled", "failed"].contains(record.state) else { throw WozaiError.storage }
        if ["saved", "received"].contains(record.state) { notices.append(record); return [] }
        record.manualPause = false
        if record.queued {
            if record.state != "queued" { record.state = "queued"; queuedCount += 1; try save(record) }
            return try startNext()
        }
        try save(record)
        if record.outgoing { try restoreSender(record) }
        return [packet(record, .status)]
    }
    private func restoreSender(_ record: AttachmentRecord) throws {
        guard outgoing == nil || outgoing?.record.id == record.id else { throw WozaiError.busy }
        guard reserved == nil || reserved?.id == record.id else { throw WozaiError.busy }
        try verifySource(record)
        outgoing = Task(record, try openFile(record.path, writing: false))
        reserved = nil
    }
    public func pause(_ id: String, cancel: Bool) throws -> [AttachmentPacket] {
        if var record = try records().first(where: { $0.id == id && $0.queued && !["canceled", "failed"].contains($0.state) }) {
            if record.state == "queued" { queuedCount = max(0, queuedCount - 1) }
            record.state = cancel ? "canceled" : "paused"; record.manualPause = !cancel
            try save(record); return []
        }
        guard let task = [outgoing, incoming].compactMap({ $0 }).first(where: { $0.record.id == id }) else {
            guard cancel, var record = try records().first(where: { $0.id == id && !["saved", "received"].contains($0.state) }) else { throw WozaiError.storage }
            record.state = "canceled"; record.manualPause = false; try save(record)
            if reserved?.id == id { reserved = nil }
            return []
        }
        guard cancel || task.record.state != "offered" else { throw WozaiError.busy }
        task.active = false; task.record.manualPause = !cancel; task.record.state = cancel ? "canceled" : "paused"
        if !task.record.outgoing {
            try task.file.truncate(atOffset: task.verified); try task.file.synchronize(); task.record.offset = task.verified
            task.whole = try hashPrefix(task.file, task.verified); task.record.digest = Data(task.whole.finalize())
        }
        try save(task.record)
        var control = packet(task.record, cancel ? .cancel : .pause); if cancel { control.reason = "canceled" }
        if cancel {
            try task.file.close()
            if task.record.outgoing { outgoing = nil } else { incoming = nil }
        }
        return [control]
    }
    public func disconnect() throws {
        for task in [outgoing, incoming].compactMap({ $0 }) {
            if ["saved", "received", "canceled", "failed"].contains(task.record.state) { continue }
            task.active = false; task.record.state = "paused"
            if !task.record.outgoing { try task.file.truncate(atOffset: task.record.offset); try task.file.synchronize() }
            try save(task.record); try task.file.close()
        }
        outgoing = nil; incoming = nil
    }
    private func safeFilename(_ name: String) -> String {
        var result = ""
        for scalar in name.unicodeScalars {
            let character = "/\\:".unicodeScalars.contains(scalar) ? "_" : String(scalar)
            if result.utf8.count + character.utf8.count > 180 { break }
            result += character
        }
        return result.isEmpty ? "file" : result
    }
    private func verifySource(_ record: AttachmentRecord) throws {
        let values = try record.path.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey])
        guard values.isRegularFile == true, let size = values.fileSize, size >= 0, UInt64(size) == record.info.size,
              values.contentModificationDate == record.modified else { throw WozaiError.storage }
    }
    private func openFile(_ url: URL, writing: Bool) throws -> FileHandle {
        let fd = Darwin.open(url.path, (writing ? O_RDWR : O_RDONLY) | O_NOFOLLOW | O_NONBLOCK)
        guard fd >= 0 else { throw WozaiError.storage }
        var metadata = stat()
        guard Darwin.fstat(fd, &metadata) == 0, metadata.st_mode & S_IFMT == S_IFREG else { Darwin.close(fd); throw WozaiError.storage }
        return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
    private func hashPrefix(_ file: FileHandle, _ count: UInt64) throws -> SHA256 {
        try file.seek(toOffset: 0); var hash = SHA256(), left = count
        while left > 0 {
            let bytes = try file.read(upToCount: Int(min(left, UInt64(AttachmentInfo.chunkBytes)))) ?? Data()
            guard !bytes.isEmpty else { throw WozaiError.storage }
            hash.update(data: bytes); left -= UInt64(bytes.count)
        }
        return hash
    }
    private func syncDirectory(_ url: URL) throws {
        let fd = Darwin.open(url.path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW)
        guard fd >= 0 else { throw WozaiError.storage }; defer { Darwin.close(fd) }
        guard fsync(fd) == 0 else { throw WozaiError.storage }
    }
}
