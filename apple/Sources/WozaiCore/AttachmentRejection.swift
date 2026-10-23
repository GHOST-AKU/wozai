import Foundation

/// This text-first client explicitly rejects valid file offers without sending save receipts.
enum AttachmentRejection {
    private static let mimeBytes = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#$&^_.+/-".utf8)

    static func reply(to bytes: Data) throws -> NIMFrame? {
        var input = WireReader(bytes)
        guard try input.u32() == bytes.count - 4, try input.u32() == 0x46543033 else { throw WozaiError.protocolViolation }
        let kind = try input.byte(), fromSender = try input.byte()
        let id = try input.text(maximum: 36), source = try input.text(maximum: 36), connection = try input.text(maximum: 128)
        let size = try input.u64(), offset = try input.u64(), window = try input.u64(), verified = try input.u64(), durable = try input.u64()
        let reason = try input.text(maximum: 16)
        guard validID(id), validID(source), (validID(connection) || (connection.utf8.count == 64 && connection.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) })),
              size <= 10_737_418_240, offset <= size, fromSender <= 1 else { throw WozaiError.protocolViolation }
        if kind == 7 || kind == 9 || kind == 10 {
            // A sender can pause or request resume while our offer refusal is still in flight.
            let validReason = kind == 7 ? ["", "rejected", "canceled", "failed", "busy", "paused", "tooLarge"].contains(reason) : reason.isEmpty
            guard validReason, window == 0, verified == 0, durable == 0,
                  try input.byte() == 0, try input.byte() == 0, try input.u32() == 0 else { throw WozaiError.protocolViolation }
            try input.finish()
            if kind == 9 && fromSender == 1 { return rejection(id: id, source: source, connection: connection, size: size) }
            return nil
        }
        guard kind == 0, fromSender == 1, offset == 0, window == 0, verified == 0, durable == 0, reason.isEmpty,
              try input.byte() == 1 else { throw WozaiError.protocolViolation }
        guard try input.text(maximum: 36) == id else { throw WozaiError.protocolViolation }
        let name = try input.text(maximum: 1024), mime = try input.text(maximum: 127)
        guard hasJavaTrimmedContent(name), name.unicodeScalars.count <= 255,
              !name.unicodeScalars.contains(where: { $0.value <= 31 || (127...159).contains($0.value) }),
              !mime.isEmpty, mime.utf8.allSatisfy({ mimeBytes.contains($0) }),
              try input.u64() == size, try input.u64() <= UInt64(Int64.max) else { throw WozaiError.protocolViolation }
        // V2 OFFER metadata cannot carry a precomputed digest; END supplies the whole-file hash.
        guard try input.byte() == 0 else { throw WozaiError.protocolViolation }
        guard try input.byte() == 0, try input.u32() == 0 else { throw WozaiError.protocolViolation }
        try input.finish()
        return rejection(id: id, source: source, connection: connection, size: size)
    }

    private static func rejection(id: String, source: String, connection: String, size: UInt64) -> NIMFrame {
        var packet = WireWriter(); packet.u32(0x46543033); packet.byte(7); packet.byte(0)
        packet.text(id); packet.text(source); packet.text(connection); packet.u64(size)
        for _ in 0..<4 { packet.u64(0) }
        packet.text("rejected"); packet.byte(0); packet.byte(0); packet.u32(0)
        var framed = WireWriter(); framed.u32(UInt32(packet.data.count)); framed.data += packet.data
        return NIMFrame(type: .attachment, binary: framed.data)
    }
}
