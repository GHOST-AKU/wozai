import XCTest
@testable import WozaiCore

// The same pinned Cacophony known-answer fixture as the production Java checks.
final class NoiseTests: XCTestCase {
    func testIndependentXXVectorAndBidirectionalTransport() throws {
        let a = try NoiseHandshake(initiator: true, staticPrivateKey: hex("e61ef9919cde45dd5f82166404bd08e38bceb5dfdfded0a34c8df7ed542214d1"), ephemeralPrivateKey: hex("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a"), prologue: hex("4a6f686e2047616c74"))
        let b = try NoiseHandshake(initiator: false, staticPrivateKey: hex("4a3acbfdb163dec651dfa3194dece676d437029c62a408b4c5ea9114246e4893"), ephemeralPrivateKey: hex("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b"), prologue: hex("4a6f686e2047616c74"))
        let messages: [(String, String)] = [
            ("4c756477696720766f6e204d69736573", "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"),
            ("4d757272617920526f746862617264", "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843757117acceb05bd7a45733bc22015c97a9d0cbaf41b80446d5988ff5127235d76b79eade70f473d6a4ef521fdcbeda5340d01e028ba793fc059f2724a83af05f12dda0448a7621a926b379a92477fd"),
            ("462e20412e20486179656b", "c90f1cf77eba4e50edb038991565e36c9758943a989229b6051244dc4fbecb6946744b401af2ee1a5881b65fbb87fd07cb6a328ececc9ce6ce84c399dc332d4fd521fa4bb7f467ce909395"),
            ("4361726c204d656e676572", "bc3fa77f6aca3e8466d7dc6bea10013e88a6a29add5132b461806c"),
            ("4a65616e2d426170746973746520536179", "250b01074cdfe0df2ecf8ccbf1737b15a2ddb5b52fd9a396604e9c793cee3b3bb9"),
            ("457567656e2042f6686d20766f6e2042617765726b", "449d4d433b3cdc3d02bf6fc881774b9df54366ebcffb9689bb13f14709822cd7ef42bcdb4d"),
        ]
        for index in 0..<3 {
            let sender = index.isMultiple(of: 2) ? a : b
            let receiver = index.isMultiple(of: 2) ? b : a
            let plain = hex(messages[index].0)
            let wire = try sender.write(plain)
            XCTAssertEqual(wire, hex(messages[index].1), "Independent handshake ciphertext at message \(index)")
            XCTAssertEqual(try receiver.read(wire), plain)
        }
        XCTAssertEqual(a.handshakeHash, hex("1b7aefb1125762aa21a252890d00af54519638b76437444538f9a52f21e2e0dc"))
        XCTAssertEqual(b.handshakeHash, a.handshakeHash)
        var pa = try a.split()
        var pb = try b.split()
        for index in 3..<6 {
            let plain = hex(messages[index].0)
            let wire = try index.isMultiple(of: 2) ? pa.sending.seal(plain) : pb.sending.seal(plain)
            XCTAssertEqual(wire, hex(messages[index].1))
            let decoded = try index.isMultiple(of: 2) ? pb.receiving.open(wire) : pa.receiving.open(wire)
            XCTAssertEqual(decoded, plain)
        }
    }
}

func hex(_ value: String) -> Data {
    Data(stride(from: 0, to: value.count, by: 2).map { offset in
        let start = value.index(value.startIndex, offsetBy: offset)
        return UInt8(value[start..<value.index(start, offsetBy: 2)], radix: 16)!
    })
}
