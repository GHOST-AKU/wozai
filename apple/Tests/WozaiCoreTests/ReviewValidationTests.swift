import CryptoKit
import XCTest
@testable import WozaiCore

final class ReviewValidationTests: XCTestCase {
    private let id = "00000000-0000-0000-0000-000000000001"
    private let other = "00000000-0000-0000-0000-000000000002"

    func testTextUsesJavaTrimEmptinessOnEncodeAndDecode() throws {
        for value in ["\u{0085}", "\u{00a0}", "\u{1680}", "\u{2003}", "\u{2028}", "\u{2029}", "\u{202f}", "\u{205f}", "\u{3000}", " \u{00a0}\n"] {
            let frame = NIMFrame(type: .text, id: id, body: value, timestamp: 1)
            var raw = WireWriter()
            raw.u32(0x4e494d34); raw.byte(4); raw.byte(3); raw.u64(1); raw.text(id); raw.text(value)
            XCTAssertEqual(try NIMFrame.decode(raw.data), frame, "Production-valid scalar sequence: \(value.unicodeScalars.map(\.value))")
            XCTAssertEqual(try frame.encoded(), raw.data)
        }
        for value in ["", " \t\n", "\u{0000}\u{001f} "] {
            XCTAssertThrowsError(try NIMFrame(type: .text, id: id, body: value).encoded())
        }
    }

    func testPeerClaimAcceptsProductionValidUnicodeWhitespace() throws {
        let root = P256.Signing.PrivateKey().publicKey.derRepresentation
        for nickname in ["\u{00a0}", "\u{2003}", "\u{2028}", "\u{3000}", String(repeating: "🙂", count: 32)] {
            var raw = WireWriter()
            raw.byte(4); raw.byte(1); raw.shortBytes(Data(id.utf8)); raw.shortBytes(Data(nickname.utf8)); raw.shortBytes(root)
            raw.data += Data(repeating: 7, count: 32); raw.u32(15); raw.u64(10_737_418_240); raw.u32(48 * 1024)
            let decoded = try PeerClaim.decode(raw.data, expectedInitiator: false, localID: other)
            XCTAssertEqual(decoded.nickname, nickname)
            XCTAssertEqual(try decoded.encoded(), raw.data)
        }
        for nickname in ["", " ", "\t", "\u{0085}", "a\u{007f}", String(repeating: "🙂", count: 33)] {
            XCTAssertThrowsError(try PeerClaim(initiator: false, id: id, nickname: nickname, root: root,
                noisePublic: Data(repeating: 7, count: 32), capabilities: 15).encoded())
        }
    }

    func testMessageUUIDsAcceptUppercaseWithoutRelaxingClaimIDs() throws {
        let uppercase = "ABCDEF01-2345-6789-ABCD-EF0123456789"
        for kind in [NIMFrame.Kind.text, .ack] {
            let body = kind == .text ? "Message UUID case is preserved" : ""
            var raw = WireWriter()
            raw.u32(0x4e494d34); raw.byte(4); raw.byte(kind.rawValue); raw.u64(1); raw.text(uppercase); raw.text(body)
            let frame = NIMFrame(type: kind, id: uppercase, body: body, timestamp: 1)
            XCTAssertEqual(try NIMFrame.decode(raw.data), frame)
            XCTAssertEqual(try frame.encoded(), raw.data)
        }
        for invalid in ["{\(uppercase)}", uppercase.replacingOccurrences(of: "-", with: ""), "G" + String(uppercase.dropFirst())] {
            XCTAssertThrowsError(try NIMFrame(type: .ack, id: invalid).encoded())
        }
        let root = P256.Signing.PrivateKey().publicKey.derRepresentation
        XCTAssertThrowsError(try PeerClaim(initiator: false, id: uppercase, nickname: "Peer", root: root,
            noisePublic: Data(repeating: 7, count: 32), capabilities: 15).encoded())
    }

    func testMonotonicSessionDeadlinesAndConsentHeartbeatReset() {
        let start = ContinuousClock.now
        var deadlines = SessionDeadlines(now: start)
        func action(_ seconds: Int64, peer: Bool, ready: Bool) -> SessionDeadlines.Action {
            deadlines.action(peerKnown: peer, ready: ready, at: start.advanced(by: .seconds(seconds)))
        }
        XCTAssertEqual(action(15, peer: false, ready: false), .none)
        XCTAssertEqual(action(16, peer: false, ready: false), .timeout)
        XCTAssertEqual(action(60, peer: true, ready: false), .none)
        XCTAssertEqual(action(61, peer: true, ready: false), .timeout)
        XCTAssertEqual(action(10, peer: true, ready: true), .none)
        XCTAssertEqual(action(11, peer: true, ready: true), .ping)
        XCTAssertEqual(action(45, peer: true, ready: true), .ping)
        XCTAssertEqual(action(46, peer: true, ready: true), .timeout)
        // Time spent waiting for consent must not exhaust a newly ready session's heartbeat.
        deadlines.recordRead(at: start.advanced(by: .seconds(60)))
        XCTAssertEqual(action(70, peer: true, ready: true), .none)
        XCTAssertEqual(action(71, peer: true, ready: true), .ping)
        XCTAssertEqual(action(105, peer: true, ready: true), .ping)
        XCTAssertEqual(action(106, peer: true, ready: true), .timeout)
    }

    func testMalformedAttachmentMetadataFailsBeforeARefusal() throws {
        for name in [" ", "\t\n", "a\u{0000}b", "a\u{007f}b", "a\u{0085}b", String(repeating: "a", count: 256), String(repeating: "e\u{0301}", count: 128)] {
            XCTAssertThrowsError(try AttachmentRejection.reply(to: offer(name: name, mime: "text/plain")), "Invalid filename: \(name.debugDescription)")
        }
        for mime in ["", "text/plain; charset=utf-8", "text/plain ", "图片/png", "text:plain"] {
            XCTAssertThrowsError(try AttachmentRejection.reply(to: offer(name: "a.txt", mime: mime)), "Invalid MIME: \(mime)")
        }
        for name in ["\u{00a0}", "document.txt", String(repeating: "🙂", count: 255)] {
            XCTAssertNotNil(try AttachmentRejection.reply(to: offer(name: name, mime: "APPLICATION/OCTET-STREAM")))
        }
    }

    private func offer(name: String, mime: String) -> Data {
        var body = WireWriter()
        body.u32(0x46543033); body.byte(0); body.byte(1); body.text(id); body.text(other); body.text(String(repeating: "a", count: 64))
        body.u64(3); for _ in 0..<4 { body.u64(0) }; body.text(""); body.byte(1)
        body.text(id); body.text(name); body.text(mime); body.u64(3); body.u64(1234); body.byte(0); body.byte(0); body.u32(0)
        var framed = WireWriter(); framed.u32(UInt32(body.data.count)); framed.data += body.data
        return framed.data
    }
}
