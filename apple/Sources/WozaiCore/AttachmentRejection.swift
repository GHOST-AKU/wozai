import Foundation

/// This text-first client explicitly rejects valid file offers without sending save receipts.
enum AttachmentRejection {
    static func reply(to bytes: Data) throws -> NIMFrame? {
        var input = WireReader(bytes)
        guard try input.u32() == bytes.count - 4, try input.u32() == 0x46543033 else { throw WozaiError.protocolViolation }
        let kind = try input.byte(), fromSender = try input.byte()
        let id = try input.text(maximum: 36), source = try input.text(maximum: 36), connection = try input.text(maximum: 128)
        let size = try input.u64(), offset = try input.u64(), window = try input.u64(), verified = try input.u64(), durable = try input.u64()
        let reason = try input.text(maximum: 16)
        guard validID(id), validID(source), (validID(connection) || (connection.utf8.count == 64 && connection.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) })),
              size <= 10_737_418_240, fromSender <= 1 else { throw WozaiError.protocolViolation }
        if kind == 7 {
            guard ["rejected", "canceled", "failed", "busy", "paused", "tooLarge"].contains(reason),
                  try input.byte() == 0, try input.byte() == 0, try input.u32() == 0 else { throw WozaiError.protocolViolation }
            try input.finish()
            return nil
        }
        guard kind == 0, fromSender == 1, offset == 0, window == 0, verified == 0, durable == 0, reason.isEmpty,
              try input.byte() == 1 else { throw WozaiError.protocolViolation }
        guard try input.text(maximum: 36) == id else { throw WozaiError.protocolViolation }
        let name = try input.text(maximum: 1024), mime = try input.text(maximum: 127)
        guard !name.isEmpty, !mime.isEmpty, try input.u64() == size, try input.u64() <= UInt64(Int64.max) else { throw WozaiError.protocolViolation }
        let hash = try input.byte(); guard hash <= 1 else { throw WozaiError.protocolViolation }
        if hash == 1 { _ = try input.bytes(32) }
        guard try input.byte() == 0, try input.u32() == 0 else { throw WozaiError.protocolViolation }
        try input.finish()
        var packet = WireWriter(); packet.u32(0x46543033); packet.byte(7); packet.byte(0)
        packet.text(id); packet.text(source); packet.text(connection); packet.u64(size)
        for _ in 0..<4 { packet.u64(0) }
        packet.text("rejected"); packet.byte(0); packet.byte(0); packet.u32(0)
        var framed = WireWriter(); framed.u32(UInt32(packet.data.count)); framed.data += packet.data
        return NIMFrame(type: .attachment, binary: framed.data)
    }
}
