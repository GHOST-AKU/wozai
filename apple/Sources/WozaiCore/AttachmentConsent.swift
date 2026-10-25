import Foundation

/// UI callbacks belong to one presentation, even when a peer reuses a file ID later.
public struct AttachmentConsent: Sendable {
    public struct Prompt: Identifiable, Sendable {
        public let id: UUID
        public let info: AttachmentInfo
    }
    public private(set) var pending: AttachmentInfo?
    public private(set) var token = UUID()
    public var prompt: Prompt? { pending.map { Prompt(id: token, info: $0) } }
    public init() {}
    public mutating func replace(_ info: AttachmentInfo?) { token = UUID(); pending = info }
    public mutating func take(expected: UUID) -> AttachmentInfo? {
        guard token == expected else { return nil }
        let result = pending; replace(nil); return result
    }
}
