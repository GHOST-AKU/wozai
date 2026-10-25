import XCTest
import CryptoKit
@testable import WozaiCore

final class WireTests: XCTestCase {
    private let messageID = "00000000-0000-0000-0000-000000000001"

    func testTextWireMatchesExistingBigEndianNIM4Format() throws {
        let message = NIMFrame(type: .text, id: messageID, body: "我在🙂", timestamp: 1)
        let expected = hex("4e494d34040300000000000000010000002430303030303030302d303030302d303030302d303030302d3030303030303030303030310000000ae68891e59ca8f09f9982")
        XCTAssertEqual(try message.encoded(), expected)
        XCTAssertEqual(try NIMFrame.decode(expected), message)
    }

    func testByteLimitDoesNotTruncateDraftOrEmoji() throws {
        let boundary = String(repeating: "🙂", count: 2048)
        XCTAssertNoThrow(try NIMFrame(type: .text, id: messageID, body: boundary, timestamp: 0).encoded())
        XCTAssertThrowsError(try NIMFrame(type: .text, id: messageID, body: boundary + "a", timestamp: 0).encoded())
        XCTAssertThrowsError(try NIMFrame(type: .text, id: "not-a-uuid", body: "hello", timestamp: 0).encoded())
        XCTAssertThrowsError(try NIMFrame(type: .ready, id: messageID, body: "", timestamp: 0).encoded())
    }

    func testMalformedAndOversizedLengthsAreRejectedBeforeAllocation() throws {
        let valid = try NIMFrame(type: .text, id: messageID, body: "a", timestamp: 1).encoded()
        for length in 0..<valid.count { XCTAssertThrowsError(try NIMFrame.decode(Data(valid.prefix(length)))) }
        XCTAssertThrowsError(try NIMFrame.decode(valid + Data([0])))
        var invalid = valid
        invalid[14] = 0x7f
        XCTAssertThrowsError(try NIMFrame.decode(invalid))
        invalid = valid
        invalid[invalid.count - 1] = 0xff
        XCTAssertThrowsError(try NIMFrame.decode(invalid))
        XCTAssertThrowsError(try Envelope.header(kind: .record, payloadSize: 48 * 1024 + 17))
    }

    func testCanonicalP256ClaimAndRolesAreBound() throws {
        let root = P256.Signing.PrivateKey().publicKey.derRepresentation
        let claim = PeerClaim(initiator: false, id: messageID, nickname: "平板🙂", root: root, noisePublic: Data(repeating: 7, count: 32), capabilities: 15)
        let decoded = try PeerClaim.decode(claim.encoded(), expectedInitiator: false, localID: "00000000-0000-0000-0000-000000000002")
        XCTAssertEqual(decoded.root, root)
        XCTAssertEqual(decoded.nickname, claim.nickname)
        XCTAssertThrowsError(try PeerClaim.decode(claim.encoded(), expectedInitiator: true, localID: "00000000-0000-0000-0000-000000000002"))
        XCTAssertThrowsError(try PeerClaim.decode(claim.encoded(), expectedInitiator: false, localID: messageID))
        var bad = claim
        bad.capabilities = 0
        XCTAssertThrowsError(try PeerClaim.decode(bad.encoded(), expectedInitiator: false, localID: "00000000-0000-0000-0000-000000000002"))
        bad = claim
        bad.root = root + Data([0])
        XCTAssertThrowsError(try bad.encoded())
    }

    func testSafetyCodeIsSymmetricAndChangesWithEitherIdentity() throws {
        let a = P256.Signing.PrivateKey().publicKey.derRepresentation
        let b = P256.Signing.PrivateKey().publicKey.derRepresentation
        let c = P256.Signing.PrivateKey().publicKey.derRepresentation
        XCTAssertEqual(try SafetyCode.make(a, b), try SafetyCode.make(b, a))
        XCTAssertNotEqual(try SafetyCode.make(a, b), try SafetyCode.make(a, c))
        XCTAssertEqual(try SafetyCode.make(a, b).count, 64)
        XCTAssertThrowsError(try SafetyCode.make(Data([0]), b))
    }
}
