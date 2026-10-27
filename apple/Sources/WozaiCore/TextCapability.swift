import CryptoKit
import Foundation

public enum TextLimits {
    public static let legacy = 8192, extended = 32768, draft = 256 * 1024
    public static func canSend(_ text: String, limit: Int) -> Bool {
        [legacy, extended].contains(limit) && text.utf8.count <= limit && hasJavaTrimmedContent(text)
    }
}

/// Existing peers ignore PONG timestamps; extension tokens bind to the authenticated session.
struct TextCapability: Sendable {
    let request: UInt64
    let response: UInt64
    private var started = false, replied = false
    private(set) var incoming = TextLimits.legacy, outgoing = TextLimits.legacy
    init(generation: String) {
        func token(_ kind: String, _ prefix: UInt64) -> UInt64 {
            SHA256.hash(data: Data(("wozai-text-capability-v1\0" + kind + "\0" + generation).utf8)).prefix(6).reduce(prefix) { ($0 << 8) | UInt64($1) }
        }
        request = token("request", 0x574f); response = token("response", 0x5752)
    }
    mutating func start() -> UInt64? { guard !started else { return nil }; started = true; return request }
    mutating func receive(_ time: UInt64) -> UInt64? {
        if time == request && !replied { replied = true; incoming = TextLimits.extended; return response }
        if time == response && started { outgoing = TextLimits.extended }
        return nil
    }
}
