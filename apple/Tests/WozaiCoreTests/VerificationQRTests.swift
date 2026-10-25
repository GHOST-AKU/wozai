import Foundation
import XCTest
@testable import WozaiCore

final class VerificationQRTests: XCTestCase {
    func testNativeQRRoundTripAndFullRootComparison() throws {
        let a = DeviceIdentity().root, b = DeviceIdentity().root, c = DeviceIdentity().root
        let payload = try VerificationQR.payload(local: a, remote: b)
        XCTAssertEqual(payload, try VerificationQR.payload(local: b, remote: a))
        XCTAssertTrue(try VerificationQR.matches(payload, local: a, remote: b))
        XCTAssertFalse(try VerificationQR.matches(payload, local: a, remote: c))
        for altered in [payload + "\n", payload.lowercased(), String(payload.dropLast()), "https://example.com/" + payload] {
            XCTAssertFalse(try VerificationQR.matches(altered, local: a, remote: b))
        }
        let image = try VerificationQR.png(local: a, remote: b)
        XCTAssertEqual(try VerificationQR.read(image), [payload])
        XCTAssertThrowsError(try VerificationQR.read(Data("not an image".utf8)))
        XCTAssertThrowsError(try VerificationQR.read(Data(repeating: 0, count: 16 * 1024 * 1024 + 1)))
    }
}
