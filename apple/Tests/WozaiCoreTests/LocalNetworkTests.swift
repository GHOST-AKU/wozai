import XCTest
@testable import WozaiCore

final class LocalNetworkTests: XCTestCase {
    func testManualAddressAdmission() {
        for host in ["127.0.0.1", "10.0.0.5", "172.16.1.5", "172.31.1.5", "192.168.1.2", "169.254.1.2", "::1", "::ffff:192.168.1.2", "fe80::1%en0", "fd00::1", "laptop.local", "laptop.local."] {
            XCTAssertTrue(LocalEndpoint.allows(host), host)
        }
        for host in ["", "8.8.8.8", "172.32.1.1", "192.169.1.1", "example.com", "https://laptop.local", "bad name.local", "2001:4860:4860::8888"] {
            XCTAssertFalse(LocalEndpoint.allows(host), host)
        }
    }

    func testValidFileOfferGetsExplicitRefusalAndTruncationsNeverGetReceipt() throws {
        let id = UUID().uuidString.lowercased(), source = UUID().uuidString.lowercased(), connection = String(repeating: "a", count: 64)
        var body = WireWriter(); body.u32(0x46543033); body.byte(0); body.byte(1)
        body.text(id); body.text(source); body.text(connection); body.u64(3)
        for _ in 0..<4 { body.u64(0) }
        body.text(""); body.byte(1); body.text(id); body.text("hello.txt"); body.text("text/plain"); body.u64(3); body.u64(1234)
        body.byte(0); body.byte(0); body.u32(0)
        var offer = WireWriter(); offer.u32(UInt32(body.data.count)); offer.data += body.data
        let reply = try XCTUnwrap(AttachmentRejection.reply(to: offer.data))
        XCTAssertEqual(reply.type, .attachment)
        var input = WireReader(reply.binary)
        XCTAssertEqual(try input.u32(), UInt32(reply.binary.count - 4)); XCTAssertEqual(try input.u32(), 0x46543033)
        XCTAssertEqual(try input.byte(), 7); XCTAssertEqual(try input.byte(), 0)
        XCTAssertEqual(try input.text(maximum: 36), id); XCTAssertEqual(try input.text(maximum: 36), source)
        XCTAssertEqual(try input.text(maximum: 64), connection); XCTAssertEqual(try input.u64(), 3)
        for _ in 0..<4 { XCTAssertEqual(try input.u64(), 0) }
        XCTAssertEqual(try input.text(maximum: 16), "rejected")
        XCTAssertNil(try AttachmentRejection.reply(to: reply.binary))
        for length in 0..<offer.data.count { XCTAssertThrowsError(try AttachmentRejection.reply(to: offer.data.prefix(length))) }
    }
}
