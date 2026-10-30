import CryptoKit
import Foundation

public enum WozaiError: Error, LocalizedError {
    case protocolViolation, invalidText, identityChanged, storage, timeout, closed, busy
    public var errorDescription: String? {
        switch self {
        case .protocolViolation: return String(localized: "The peer sent an invalid or incompatible message.")
        case .invalidText: return String(localized: "Enter valid text within the displayed UTF-8 byte limit. Your draft is preserved.")
        case .identityChanged: return String(localized: "This device’s identity changed. Verify it before reconnecting.")
        case .storage: return String(localized: "The message could not be saved. No delivery receipt was sent.")
        case .timeout: return String(localized: "The connection timed out.")
        case .closed: return String(localized: "The connection ended.")
        case .busy: return String(localized: "Finish the current connection before starting another.")
        }
    }
}

struct WireWriter {
    var data = Data()
    mutating func byte(_ value: UInt8) { data.append(value) }
    mutating func u16(_ value: Int) { byte(UInt8((value >> 8) & 255)); byte(UInt8(value & 255)) }
    mutating func u32(_ value: UInt32) { for shift in stride(from: 24, through: 0, by: -8) { byte(UInt8((value >> shift) & 255)) } }
    mutating func u64(_ value: UInt64) { for shift in stride(from: 56, through: 0, by: -8) { byte(UInt8((value >> shift) & 255)) } }
    mutating func text(_ value: String) { let bytes = Data(value.utf8); u32(UInt32(bytes.count)); data += bytes }
    mutating func shortBytes(_ value: Data) { u16(value.count); data += value }
}

struct WireReader {
    private let data: Data
    private var offset = 0
    init(_ data: Data) { self.data = Data(data) }
    var remaining: Int { data.count - offset }
    mutating func bytes(_ count: Int) throws -> Data {
        guard count >= 0, count <= remaining else { throw WozaiError.protocolViolation }
        defer { offset += count }
        return data.subdata(in: offset..<(offset + count))
    }
    mutating func byte() throws -> UInt8 { try bytes(1)[0] }
    mutating func u16() throws -> Int { let b = try bytes(2); return Int(b[0]) << 8 | Int(b[1]) }
    mutating func u32() throws -> UInt32 { try bytes(4).reduce(0) { ($0 << 8) | UInt32($1) } }
    mutating func u64() throws -> UInt64 { try bytes(8).reduce(0) { ($0 << 8) | UInt64($1) } }
    mutating func shortBytes(maximum: Int) throws -> Data {
        let count = try u16(); guard count <= maximum else { throw WozaiError.protocolViolation }; return try bytes(count)
    }
    mutating func text(maximum: Int, short: Bool = false) throws -> String {
        let count = try short ? u16() : Int(u32())
        guard count <= maximum, let value = String(data: try bytes(count), encoding: .utf8) else { throw WozaiError.protocolViolation }
        return value
    }
    func finish() throws { guard remaining == 0 else { throw WozaiError.protocolViolation } }
}

func validID(_ id: String) -> Bool {
    id.utf8.count == 36 && UUID(uuidString: id) != nil && id == id.lowercased()
}

// Message records accept either hex case; claims and transfer IDs remain lowercase-only.
private func validFrameID(_ id: String) -> Bool {
    id.utf8.count == 36 && UUID(uuidString: id) != nil
}

// Match Java String.trim().isEmpty() without changing the original payload.
func hasJavaTrimmedContent(_ value: String) -> Bool {
    value.unicodeScalars.contains { $0.value > 0x20 }
}

public struct NIMFrame: Equatable, Sendable {
    public enum Kind: UInt8, Sendable { case ready = 2, text = 3, ack = 4, bye = 5, ping = 6, pong = 7, attachment = 16, readReceipt = 20 }
    public let type: Kind
    public let id: String
    public let body: String
    public let timestamp: UInt64
    public let binary: Data

    public init(type: Kind, id: String = "", body: String = "", timestamp: UInt64 = 0, binary: Data = Data()) {
        self.type = type; self.id = id; self.body = body; self.timestamp = timestamp; self.binary = binary
    }

    private func validate(textLimit: Int) throws {
        guard [TextLimits.legacy, TextLimits.extended].contains(textLimit) else { throw WozaiError.protocolViolation }
        guard timestamp <= UInt64(Int64.max) else { throw WozaiError.protocolViolation }
        switch type {
        case .text:
            guard validFrameID(id), hasJavaTrimmedContent(body), body.utf8.count <= textLimit, binary.isEmpty else { throw WozaiError.invalidText }
        case .ack:
            guard validFrameID(id), body.isEmpty, binary.isEmpty else { throw WozaiError.protocolViolation }
        case .readReceipt:
            guard id.isEmpty, body.isEmpty, timestamp == 0 else { throw WozaiError.protocolViolation }
            _ = try ReadReceiptPacket.decode(binary)
        case .attachment:
            guard id.isEmpty, body.isEmpty, timestamp == 0, binary.count <= 40 * 1024 else { throw WozaiError.protocolViolation }
            _ = try AttachmentPacket.decode(binary)
        default:
            guard id.isEmpty, body.isEmpty, binary.isEmpty else { throw WozaiError.protocolViolation }
        }
    }

    public func encoded(textLimit: Int = TextLimits.legacy) throws -> Data {
        try validate(textLimit: textLimit)
        var out = WireWriter(); out.u32(0x4e494d34); out.byte(4); out.byte(type.rawValue); out.u64(timestamp); out.text(id); out.text(body)
        if type == .attachment || type == .readReceipt { out.u32(UInt32(binary.count)); out.data += binary }
        guard out.data.count <= 48 * 1024 else { throw WozaiError.protocolViolation }
        return out.data
    }

    public static func decode(_ data: Data, textLimit: Int = TextLimits.legacy) throws -> NIMFrame {
        guard data.count >= 22, data.count <= 48 * 1024 else { throw WozaiError.protocolViolation }
        var input = WireReader(data)
        guard try input.u32() == 0x4e494d34, try input.byte() == 4, let type = Kind(rawValue: try input.byte()) else { throw WozaiError.protocolViolation }
        let time = try input.u64(), id = try input.text(maximum: 64), body = try input.text(maximum: type == .text ? textLimit : TextLimits.legacy)
        var binary = Data()
        if type == .attachment || type == .readReceipt {
            let count = try input.u32(); guard count <= 40 * 1024 else { throw WozaiError.protocolViolation }
            binary = try input.bytes(Int(count))
        }
        try input.finish()
        let frame = NIMFrame(type: type, id: id, body: body, timestamp: time, binary: binary)
        try frame.validate(textLimit: textLimit)
        return frame
    }
}

public enum Envelope {
    public enum Kind: UInt8 { case handshake = 1, rootProof = 2, record = 3 }
    public static func header(kind: Kind, payloadSize: Int) throws -> Data {
        let maximum = kind == .handshake ? 2048 : kind == .rootProof ? 96 : 48 * 1024 + 16
        guard payloadSize >= 0, payloadSize <= maximum else { throw WozaiError.protocolViolation }
        var out = WireWriter(); out.u32(UInt32(payloadSize + 6)); out.u32(0x4e494d34); out.byte(4); out.byte(kind.rawValue)
        return out.data
    }
}

extension Data {
    public var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
