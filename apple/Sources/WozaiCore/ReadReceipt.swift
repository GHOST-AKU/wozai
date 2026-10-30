import CryptoKit
import Foundation

/// Wire format shared with Java: exact IDs, bounded batches, no read timestamp.
public struct ReadReceiptPacket: Equatable, Sendable {
    public enum Kind: UInt8, Sendable { case policy = 0, read = 1, ack = 2 }
    public let kind: Kind
    public let epoch: UInt64
    public let peerEpoch: UInt64
    public let enabled: Bool
    public let ids: [String]
    static let maximum = 19 + 16 * 32

    func encoded() throws -> Data {
        guard epoch > 0, epoch <= UInt64(Int64.max), peerEpoch <= UInt64(Int64.max),
              (kind == .policy ? (peerEpoch == 0 && ids.isEmpty) : (peerEpoch > 0 && !ids.isEmpty && ids.count <= 32)),
              Set(ids).count == ids.count, ids.allSatisfy(validID) else { throw WozaiError.protocolViolation }
        var out = WireWriter(); out.byte(1); out.byte(kind.rawValue); out.u64(epoch); out.u64(peerEpoch)
        out.byte(kind == .policy ? (enabled ? 1 : 0) : UInt8(ids.count))
        for id in ids {
            let hex = id.replacingOccurrences(of: "-", with: "")
            var index = hex.startIndex
            for _ in 0..<16 { let end = hex.index(index, offsetBy: 2); out.byte(UInt8(hex[index..<end], radix: 16)!); index = end }
        }
        return out.data
    }
    static func decode(_ bytes: Data) throws -> Self {
        guard bytes.count >= 19, bytes.count <= maximum else { throw WozaiError.protocolViolation }
        var input = WireReader(bytes)
        guard try input.byte() == 1, let kind = Kind(rawValue: try input.byte()) else { throw WozaiError.protocolViolation }
        let epoch = try input.u64(), peerEpoch = try input.u64(), count = try input.byte()
        guard (kind == .policy ? count <= 1 : (count > 0 && count <= 32)) else { throw WozaiError.protocolViolation }
        var ids: [String] = []
        if kind != .policy {
            for _ in 0..<count {
                let hex = try input.bytes(16).hexString
                ids.append([0..<8,8..<12,12..<16,16..<20,20..<32].map { range in String(hex[hex.index(hex.startIndex, offsetBy: range.lowerBound)..<hex.index(hex.startIndex, offsetBy: range.upperBound)]) }.joined(separator: "-"))
            }
        }
        try input.finish()
        let packet = Self(kind: kind, epoch: epoch, peerEpoch: peerEpoch, enabled: kind == .policy && count == 1, ids: ids)
        _ = try packet.encoded(); return packet
    }
    func frame() throws -> NIMFrame { NIMFrame(type: .readReceipt, binary: try encoded()) }
}

struct ReadReceiptCapability: Sendable {
    let request: UInt64, response: UInt64
    private var started = false, replied = false
    private(set) var negotiated = false
    private(set) var enabled: Bool
    private(set) var epoch: UInt64 = 1, peerEpoch: UInt64 = 0
    private var peerEnabled = false
    private var inFlight = Set<String>()
    private var incoming: [String] = []
    private var ackPending = false
    var active: Bool { negotiated && enabled && peerEnabled }
    var policy: ReadReceiptPacket { .init(kind: .policy, epoch: epoch, peerEpoch: 0, enabled: enabled, ids: []) }
    init(generation: String, enabled: Bool) {
        self.enabled = enabled
        func token(_ kind: String, _ prefix: UInt64) -> UInt64 {
            SHA256.hash(data: Data(("wozai-read-receipt-v1\0" + kind + "\0" + generation).utf8)).prefix(6).reduce(prefix) { ($0 << 8) | UInt64($1) }
        }
        request = token("request", 0x5251); response = token("response", 0x5253)
    }
    mutating func start() -> UInt64? { guard !started else { return nil }; started = true; return request }
    mutating func receiveToken(_ time: UInt64) -> UInt64? {
        if time == request && !replied { replied = true; return response }
        if time == response && started { negotiated = true }
        return nil
    }
    mutating func configure(_ value: Bool) {
        guard enabled != value else { return }; enabled = value; epoch += 1; inFlight.removeAll(); incoming.removeAll(); ackPending = false
    }
    func current(_ packet: ReadReceiptPacket) -> Bool { active && packet.epoch == peerEpoch && packet.peerEpoch == epoch }
    func outgoingCurrent(_ packet: ReadReceiptPacket) -> Bool {
        negotiated && packet.epoch == epoch && (packet.kind == .policy || (active && packet.peerEpoch == peerEpoch))
    }
    mutating func receive(_ packet: ReadReceiptPacket) throws -> Bool {
        guard replied else { throw WozaiError.protocolViolation }
        if packet.kind == .policy {
            guard packet.epoch >= peerEpoch else { return false }
            if packet.epoch == peerEpoch { guard packet.enabled == peerEnabled else { throw WozaiError.protocolViolation }; return false }
            peerEpoch = packet.epoch; peerEnabled = packet.enabled; inFlight.removeAll(); incoming.removeAll(); ackPending = false; return false
        }
        guard current(packet) else { return false }
        if packet.kind == .ack {
            guard !ackPending, Set(packet.ids) == inFlight else { return false }; ackPending = true
        } else {
            if !incoming.isEmpty { guard incoming == packet.ids else { throw WozaiError.protocolViolation }; return false }
            incoming = packet.ids
        }
        return true
    }
    mutating func send(_ ids: [String]) -> ReadReceiptPacket? {
        guard active, inFlight.isEmpty, !ids.isEmpty else { return nil }
        inFlight.formUnion(ids); return .init(kind: .read, epoch: epoch, peerEpoch: peerEpoch, enabled: false, ids: ids)
    }
    mutating func acknowledge(_ packet: ReadReceiptPacket) -> ReadReceiptPacket? {
        guard packet.kind == .read, current(packet) else { return nil }
        incoming.removeAll()
        return .init(kind: .ack, epoch: epoch, peerEpoch: peerEpoch, enabled: false, ids: packet.ids)
    }
    mutating func persisted(_ packet: ReadReceiptPacket) { if packet.kind == .ack && current(packet) { inFlight.subtract(packet.ids); ackPending = false } }
}

/// Same geometry and dwell rule as Android/desktop, independent of SwiftUI lifecycle callbacks.
public struct ReadVisibility: Sendable {
    private var peer: String?
    private var since: [String: ContinuousClock.Instant] = [:]
    private var emitted = Set<String>()
    public init() {}
    public mutating func update(peer: String, visible: Set<String>, foreground: Bool, now: ContinuousClock.Instant = .now) -> [String] {
        guard foreground else { self = Self(); return [] }
        if self.peer != peer { self = Self(); self.peer = peer }
        since = since.filter { visible.contains($0.key) }; emitted.formIntersection(visible)
        var ids: [String] = []
        for id in visible.sorted() {
            if let began = since[id], began.duration(to: now) >= .milliseconds(250), !emitted.contains(id), ids.count < 32 { emitted.insert(id); ids.append(id) }
            else if since[id] == nil { since[id] = now }
        }
        return ids
    }
    public mutating func retry(_ ids: [String]) { emitted.subtract(ids) }
    public static func visible(top: Double, height: Double, viewportHeight: Double) -> Bool {
        guard top.isFinite, height.isFinite, viewportHeight.isFinite, height > 0, viewportHeight > 0 else { return false }
        return max(0, min(top + height, viewportHeight) - max(0, top)) >= min(height / 2, viewportHeight / 2)
    }
}
