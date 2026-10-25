import Foundation
import XCTest
@testable import WozaiCore

final class AttachmentConsentTests: XCTestCase {
    func testNativeSheetIdentityStaysStableUntilTheOfferChanges() throws {
        let info = try AttachmentInfo(id: UUID().uuidString.lowercased(), name: "file.bin", mime: "application/octet-stream", size: 19, time: 1)
        var consent = AttachmentConsent()
        XCTAssertNil(consent.prompt)
        consent.replace(info)
        let first = try XCTUnwrap(consent.prompt)
        XCTAssertEqual(first.id, consent.prompt?.id)
        XCTAssertEqual(first.info, info)
        consent.replace(nil)
        XCTAssertNil(consent.prompt)
        consent.replace(info)
        XCTAssertNotEqual(first.id, consent.prompt?.id)
    }
    func testDismissalFromRetiredPresentationCannotRejectNewOffer() throws {
        let info = try AttachmentInfo(id: UUID().uuidString.lowercased(), name: "file.bin", mime: "application/octet-stream", size: 19, time: 1)
        var consent = AttachmentConsent()
        consent.replace(info); let previous = consent.token
        consent.replace(nil)
        consent.replace(info); let current = consent.token
        XCTAssertNotEqual(previous, current)
        XCTAssertNil(consent.take(expected: previous))
        XCTAssertEqual(consent.pending, info)
        XCTAssertEqual(consent.take(expected: current), info)
        XCTAssertNil(consent.pending)
        XCTAssertNil(consent.take(expected: current))
    }
}
