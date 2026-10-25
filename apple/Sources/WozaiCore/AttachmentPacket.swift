import Foundation

public struct AttachmentInfo: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public let name: String
    public let mime: String
    public let size: UInt64
    public let time: UInt64
    public static let maximumSize: UInt64 = 10_737_418_240
    public static let blockBytes: UInt64 = 1024 * 1024
    public static let chunkBytes = 32768

    public init(id: String, name: String, mime: String, size: UInt64, time: UInt64) throws {
        self.id = id; self.name = name; self.mime = mime; self.size = size; self.time = time
        try validate()
    }
    func validate() throws {
        let mimeBytes = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&^_.+/-".utf8)
        guard validID(id), hasJavaTrimmedContent(name), name.unicodeScalars.count <= 255,
              name.utf8.count <= 1024, !name.unicodeScalars.contains(where: { $0.value <= 31 || (127...159).contains($0.value) }),
              !mime.isEmpty, mime.utf8.count <= 127, mime.utf8.allSatisfy({ mimeBytes.contains($0) }),
              size <= Self.maximumSize, time <= UInt64(Int64.max) else { throw WozaiError.protocolViolation }
    }
    func write(to out: inout WireWriter) {
        out.text(id); out.text(name); out.text(mime); out.u64(size); out.u64(time); out.byte(0)
    }
    static func read(from input: inout WireReader) throws -> AttachmentInfo {
        let info = try AttachmentInfo(id: input.text(maximum: 36), name: input.text(maximum: 1024),
            mime: input.text(maximum: 127), size: input.u64(), time: input.u64())
        guard try input.byte() == 0 else { throw WozaiError.protocolViolation }
        return info
    }
}

/// Direction remains relative to the original file sender across reconnects.
public struct AttachmentPacket: Equatable, Sendable {
    public enum Kind: UInt8, Sendable { case offer, accept, data, blockHash, credit, end, complete, cancel, resume, status, pause }
    public var kind: Kind
    public var fromSender: Bool
    public var id: String
    public var source: String
    public var connection: String
    public var size: UInt64
    public var offset: UInt64 = 0
    public var window: UInt64 = 0
    public var verified: UInt64 = 0
    public var durable: UInt64 = 0
    public var reason = ""
    public var info: AttachmentInfo?
    public var hash: Data?
    public var payload = Data()

    public init(kind: Kind, fromSender: Bool, info: AttachmentInfo, source: String, connection: String) {
        self.kind = kind; self.fromSender = fromSender; self.id = info.id
        self.source = source; self.connection = connection; self.size = info.size
        if kind == .offer { self.info = info }
    }
    private init(kind: Kind, fromSender: Bool, id: String, source: String, connection: String, size: UInt64) {
        self.kind = kind; self.fromSender = fromSender; self.id = id
        self.source = source; self.connection = connection; self.size = size
    }
    public func reply(_ kind: Kind) -> AttachmentPacket {
        AttachmentPacket(kind: kind, fromSender: !fromSender, id: id, source: source, connection: connection, size: size)
    }
    public func validate() throws {
        let generation = validID(connection) || (connection.utf8.count == 64 && connection.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) })
        guard validID(id), validID(source), generation, size <= AttachmentInfo.maximumSize, offset <= size,
              payload.count <= AttachmentInfo.chunkBytes, UInt64(payload.count) <= size - offset,
              window <= 8 * AttachmentInfo.blockBytes, durable <= verified, verified <= offset,
              (verified % AttachmentInfo.blockBytes == 0 || verified == size),
              (durable % AttachmentInfo.blockBytes == 0 || durable == size),
              ([Kind.credit, .resume].contains(kind) || (verified == 0 && durable == 0)),
              ([Kind.blockHash, .end, .complete, .resume].contains(kind) == (hash != nil)),
              hash == nil || hash?.count == 32,
              (kind == .data) == !payload.isEmpty, (kind == .offer) == (info != nil),
              ["", "canceled", "failed", "busy", "paused", "rejected", "tooLarge"].contains(reason),
              kind == .cancel || reason.isEmpty,
              [Kind.accept, .credit, .resume].contains(kind) || window == 0,
              kind != .offer || offset == 0,
              ![Kind.end, .complete].contains(kind) || offset == size,
              ![Kind.offer, .data, .blockHash, .end].contains(kind) || fromSender,
              ![Kind.accept, .credit, .complete].contains(kind) || !fromSender else { throw WozaiError.protocolViolation }
        if let info { try info.validate(); guard info.id == id, info.size == size else { throw WozaiError.protocolViolation } }
    }
    public func encoded() throws -> Data {
        try validate()
        var body = WireWriter(); body.u32(0x46543033); body.byte(kind.rawValue); body.byte(fromSender ? 1 : 0)
        body.text(id); body.text(source); body.text(connection)
        for number in [size, offset, window, verified, durable] { body.u64(number) }
        body.text(reason); body.byte(info == nil ? 0 : 1); if let info { info.write(to: &body) }
        body.byte(hash == nil ? 0 : 1); if let hash { body.data += hash }
        body.u32(UInt32(payload.count)); body.data += payload
        var framed = WireWriter(); framed.u32(UInt32(body.data.count)); framed.data += body.data
        guard framed.data.count <= 40 * 1024 else { throw WozaiError.protocolViolation }
        return framed.data
    }
    public static func decode(_ bytes: Data) throws -> AttachmentPacket {
        guard bytes.count <= 40 * 1024 else { throw WozaiError.protocolViolation }
        var input = WireReader(bytes)
        guard try input.u32() == bytes.count - 4, try input.u32() == 0x46543033,
              let kind = Kind(rawValue: try input.byte()) else { throw WozaiError.protocolViolation }
        let sender = try input.byte(); guard sender <= 1 else { throw WozaiError.protocolViolation }
        var packet = try AttachmentPacket(kind: kind, fromSender: sender == 1, id: input.text(maximum: 36),
            source: input.text(maximum: 36), connection: input.text(maximum: 128), size: input.u64())
        packet.offset = try input.u64(); packet.window = try input.u64()
        packet.verified = try input.u64(); packet.durable = try input.u64(); packet.reason = try input.text(maximum: 16)
        let hasInfo = try input.byte(); guard hasInfo <= 1 else { throw WozaiError.protocolViolation }
        if hasInfo == 1 { packet.info = try AttachmentInfo.read(from: &input) }
        let hasHash = try input.byte(); guard hasHash <= 1 else { throw WozaiError.protocolViolation }
        if hasHash == 1 { packet.hash = try input.bytes(32) }
        let count = try input.u32(); guard count <= AttachmentInfo.chunkBytes else { throw WozaiError.protocolViolation }
        packet.payload = try input.bytes(Int(count)); try input.finish(); try packet.validate()
        return packet
    }
    public func frame() throws -> NIMFrame { try NIMFrame(type: .attachment, binary: encoded()) }
}
