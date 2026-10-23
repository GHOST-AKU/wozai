import CryptoKit
import Foundation
import Security

public struct DeviceIdentity: Sendable {
    public let id: String
    let signing: P256.Signing.PrivateKey
    let noise: Curve25519.KeyAgreement.PrivateKey
    public var root: Data { signing.publicKey.derRepresentation }
    public var fingerprint: String { Data(SHA256.hash(data: root)).hexString }

    public init() {
        id = UUID().uuidString.lowercased()
        signing = P256.Signing.PrivateKey()
        noise = Curve25519.KeyAgreement.PrivateKey()
    }

    init(id: String, signing: Data, noise: Data) throws {
        guard validID(id) else { throw WozaiError.protocolViolation }
        self.id = id
        self.signing = try P256.Signing.PrivateKey(rawRepresentation: signing)
        self.noise = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: noise)
    }
}

public enum IdentityKeychain {
    private struct Payload: Codable { let id: String; let signing: Data; let noise: Data }

    /// A missing key creates an identity; an inaccessible/corrupt key never silently replaces it.
    public static func loadOrCreate() throws -> DeviceIdentity {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrService as String: "dev.ghost.wozai.apple",
                                    kSecAttrAccount as String: "device-identity-v1"]
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess {
            guard let data = result as? Data else { throw WozaiError.storage }
            let value = try JSONDecoder().decode(Payload.self, from: data)
            return try DeviceIdentity(id: value.id, signing: value.signing, noise: value.noise)
        }
        guard status == errSecItemNotFound else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        let identity = DeviceIdentity()
        let payload = Payload(id: identity.id, signing: identity.signing.rawRepresentation, noise: identity.noise.rawRepresentation)
        var create = query
        create[kSecValueData as String] = try JSONEncoder().encode(payload)
        create[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let saved = SecItemAdd(create as CFDictionary, nil)
        guard saved == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(saved)) }
        return identity
    }
}

public struct VerifiedPeer: Identifiable, Equatable, Sendable {
    public let id: String
    public let nickname: String
    public let root: Data
    public let capabilities: UInt32
    public init(id: String, nickname: String, root: Data, capabilities: UInt32) {
        self.id = id; self.nickname = nickname; self.root = root; self.capabilities = capabilities
    }
    public var fingerprint: String { Data(SHA256.hash(data: root)).hexString }
}

struct PeerClaim {
    var initiator: Bool
    var id: String
    var nickname: String
    var root: Data
    var noisePublic: Data
    var capabilities: UInt32

    func encoded() throws -> Data {
        guard validID(id), hasJavaTrimmedContent(nickname),
              nickname.unicodeScalars.count <= 32, nickname.utf8.count <= 128,
              !nickname.unicodeScalars.contains(where: { $0.value <= 31 || (127...159).contains($0.value) }),
              noisePublic.count == 32 else { throw WozaiError.protocolViolation }
        _ = try canonicalRoot(root)
        var out = WireWriter(); out.byte(4); out.byte(initiator ? 0 : 1)
        out.shortBytes(Data(id.utf8)); out.shortBytes(Data(nickname.utf8)); out.shortBytes(root)
        out.data += noisePublic; out.u32(capabilities); out.u64(10_737_418_240); out.u32(48 * 1024)
        return out.data
    }

    static func decode(_ data: Data, expectedInitiator: Bool, localID: String) throws -> PeerClaim {
        var reader = WireReader(data)
        guard try reader.byte() == 4, try reader.byte() == (expectedInitiator ? 0 : 1) else { throw WozaiError.protocolViolation }
        let id = try reader.text(maximum: 36, short: true), nickname = try reader.text(maximum: 128, short: true)
        let root = try reader.shortBytes(maximum: 256), noise = try reader.bytes(32), capabilities = try reader.u32()
        guard capabilities & 3 == 3, capabilities & ~UInt32(15) == 0,
              try reader.u64() == 10_737_418_240, try reader.u32() == 48 * 1024, id != localID else { throw WozaiError.protocolViolation }
        try reader.finish()
        let result = PeerClaim(initiator: expectedInitiator, id: id, nickname: nickname, root: root, noisePublic: noise, capabilities: capabilities)
        guard try result.encoded() == data else { throw WozaiError.protocolViolation }
        return result
    }
}

func canonicalRoot(_ data: Data) throws -> P256.Signing.PublicKey {
    guard data.count <= 256 else { throw WozaiError.protocolViolation }
    let key = try P256.Signing.PublicKey(derRepresentation: data)
    guard key.derRepresentation == data else { throw WozaiError.protocolViolation }
    return key
}

public enum SafetyCode {
    public static func make(_ a: Data, _ b: Data) throws -> String {
        _ = try canonicalRoot(a); _ = try canonicalRoot(b)
        let first = a.lexicographicallyPrecedes(b) ? a : b
        let second = a.lexicographicallyPrecedes(b) ? b : a
        var out = WireWriter(); out.data = Data("wozai/identity-verification/v1\0".utf8)
        out.u32(UInt32(first.count)); out.data += first; out.u32(UInt32(second.count)); out.data += second
        return Data(SHA256.hash(data: out.data)).hexString
    }
}
