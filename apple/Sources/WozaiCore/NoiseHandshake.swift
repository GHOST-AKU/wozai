import CryptoKit
import Foundation

/// Noise AESGCM uses a 32-bit zero prefix and a big-endian 64-bit counter.
public struct NoiseCipher {
    private var key: SymmetricKey?
    private var nonce: UInt64

    init() { key = nil; nonce = 0 }

    public init(key: Data, nonce: UInt64 = 0) throws {
        guard key.count == 32 else { throw WozaiError.protocolViolation }
        self.key = SymmetricKey(data: key)
        self.nonce = nonce
    }

    private func iv() throws -> AES.GCM.Nonce {
        guard nonce != UInt64.max else { throw WozaiError.protocolViolation }
        var value = nonce.bigEndian
        return try withUnsafeBytes(of: &value) { try AES.GCM.Nonce(data: Data(repeating: 0, count: 4) + Data($0)) }
    }

    public mutating func seal(_ plaintext: Data, authenticating aad: Data = Data()) throws -> Data {
        guard plaintext.count <= 65_519 else { throw WozaiError.protocolViolation }
        guard let key else { return plaintext }
        let box = try AES.GCM.seal(plaintext, using: key, nonce: iv(), authenticating: aad)
        nonce += 1
        return box.ciphertext + box.tag
    }

    public mutating func open(_ ciphertext: Data, authenticating aad: Data = Data()) throws -> Data {
        guard ciphertext.count <= 65_535 else { throw WozaiError.protocolViolation }
        guard let key else { return ciphertext }
        guard ciphertext.count >= 16 else { throw WozaiError.protocolViolation }
        let box = try AES.GCM.SealedBox(nonce: iv(), ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
        let plaintext = try AES.GCM.open(box, using: key, authenticating: aad)
        nonce += 1
        return plaintext
    }
}

public struct NoiseTransport {
    public var sending: NoiseCipher
    public var receiving: NoiseCipher
}

/// XX state transitions only; all hash, DH, HMAC and AEAD primitives use CryptoKit.
public final class NoiseHandshake {
    public private(set) var handshakeHash: Data
    public private(set) var remoteStatic = Data()
    private let initiator: Bool
    private let localStatic: Curve25519.KeyAgreement.PrivateKey
    private let ephemeral: Curve25519.KeyAgreement.PrivateKey
    private var remoteEphemeral = Data()
    private var chainingKey: Data
    private var cipher = NoiseCipher()
    private var step = 0
    private var failed = false
    private var didSplit = false

    public init(initiator: Bool, staticPrivateKey: Data, ephemeralPrivateKey: Data? = nil, prologue: Data) throws {
        self.initiator = initiator
        localStatic = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: staticPrivateKey)
        ephemeral = try ephemeralPrivateKey.map { try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: $0) }
            ?? Curve25519.KeyAgreement.PrivateKey()
        let name = Data("Noise_XX_25519_AESGCM_SHA256".utf8)
        handshakeHash = name.count <= 32 ? name + Data(repeating: 0, count: 32 - name.count) : Data(SHA256.hash(data: name))
        chainingKey = handshakeHash
        mixHash(prologue)
    }

    private func mixHash(_ bytes: Data) { handshakeHash = Data(SHA256.hash(data: handshakeHash + bytes)) }

    private func derive(_ input: Data) -> (Data, Data) {
        let temporary = SymmetricKey(data: HMAC<SHA256>.authenticationCode(for: input, using: SymmetricKey(data: chainingKey)))
        let first = Data(HMAC<SHA256>.authenticationCode(for: Data([1]), using: temporary))
        let second = Data(HMAC<SHA256>.authenticationCode(for: first + Data([2]), using: temporary))
        return (first, second)
    }

    private func mixKey(_ input: Data) throws {
        let (next, key) = derive(input)
        chainingKey = next
        cipher = try NoiseCipher(key: key)
    }

    private func dh(_ key: Curve25519.KeyAgreement.PrivateKey, _ remote: Data) throws -> Data {
        let publicKey = try Curve25519.KeyAgreement.PublicKey(rawRepresentation: remote)
        let secret = try key.sharedSecretFromKeyAgreement(with: publicKey)
        return secret.withUnsafeBytes { Data($0) }
    }

    private func encryptHash(_ bytes: Data) throws -> Data {
        let encrypted = try cipher.seal(bytes, authenticating: handshakeHash)
        mixHash(encrypted)
        return encrypted
    }

    private func decryptHash(_ bytes: Data) throws -> Data {
        let plaintext = try cipher.open(bytes, authenticating: handshakeHash)
        mixHash(bytes)
        return plaintext
    }

    public func write(_ payload: Data) throws -> Data {
        guard !failed, step < 3, step.isMultiple(of: 2) == initiator, payload.count <= 1024 else { throw WozaiError.protocolViolation }
        do {
            var message = Data()
            if step < 2 {
                let publicKey = ephemeral.publicKey.rawRepresentation
                message += publicKey
                mixHash(publicKey)
            }
            if step == 1 {
                try mixKey(dh(ephemeral, remoteEphemeral))
                message += try encryptHash(localStatic.publicKey.rawRepresentation)
                try mixKey(dh(localStatic, remoteEphemeral))
            } else if step == 2 {
                message += try encryptHash(localStatic.publicKey.rawRepresentation)
                try mixKey(dh(localStatic, remoteEphemeral))
            }
            message += try encryptHash(payload)
            step += 1
            return message
        } catch { failed = true; throw error }
    }

    public func read(_ message: Data) throws -> Data {
        guard !failed, step < 3, step.isMultiple(of: 2) != initiator, message.count <= 2048 else { throw WozaiError.protocolViolation }
        do {
            var reader = WireReader(message)
            if step < 2 {
                remoteEphemeral = try reader.bytes(32)
                mixHash(remoteEphemeral)
            }
            if step == 1 {
                try mixKey(dh(ephemeral, remoteEphemeral))
                remoteStatic = try decryptHash(reader.bytes(48))
                try mixKey(dh(ephemeral, remoteStatic))
            } else if step == 2 {
                remoteStatic = try decryptHash(reader.bytes(48))
                try mixKey(dh(ephemeral, remoteStatic))
            }
            let payload = try decryptHash(reader.bytes(reader.remaining))
            step += 1
            return payload
        } catch { failed = true; throw error }
    }

    public func split() throws -> NoiseTransport {
        guard !failed, step == 3, !didSplit else { throw WozaiError.protocolViolation }
        didSplit = true
        let (first, second) = derive(Data())
        return try NoiseTransport(sending: NoiseCipher(key: initiator ? first : second), receiving: NoiseCipher(key: initiator ? second : first))
    }
}
