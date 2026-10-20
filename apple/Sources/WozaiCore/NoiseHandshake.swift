import Foundation

public struct NoiseCipher {
    public mutating func seal(_ plaintext: Data, authenticating: Data = Data()) throws -> Data { Data() }
    public mutating func open(_ ciphertext: Data, authenticating: Data = Data()) throws -> Data { Data() }
}

public struct NoiseTransport {
    public var sending = NoiseCipher()
    public var receiving = NoiseCipher()
}

public final class NoiseHandshake {
    public private(set) var handshakeHash = Data()
    public init(initiator: Bool, staticPrivateKey: Data, ephemeralPrivateKey: Data? = nil, prologue: Data) throws {}
    public func write(_ payload: Data) throws -> Data { Data() }
    public func read(_ message: Data) throws -> Data { Data() }
    public func split() throws -> NoiseTransport { NoiseTransport() }
}
