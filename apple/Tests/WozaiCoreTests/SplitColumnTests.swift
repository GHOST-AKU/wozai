import XCTest
@testable import WozaiCore

final class SplitColumnTests: XCTestCase {
    func testDefaultAndMidpointBothRemainReachable() {
        let layout = SplitColumnLayout(total: 1000, minimum: 220, detailMinimum: 340, initial: 300)
        XCTAssertEqual(layout.width(fraction: 0), 300)
        XCTAssertEqual(layout.snap(310, latched: nil), 300)
        XCTAssertEqual(layout.snap(490, latched: nil), 500)
        XCTAssertEqual(layout.snap(523, latched: 500), 500)
        XCTAssertEqual(layout.snap(534, latched: 500), 534)
        let near = SplitColumnLayout(total: 640, minimum: 220, detailMinimum: 280, initial: 300)
        XCTAssertEqual(near.snap(319, latched: 300), 320)
    }

    func testWindowResizeClampsWithoutOverwritingSavedPreference() {
        let wide = SplitColumnLayout(total: 1200, minimum: 220, detailMinimum: 340, initial: 300)
        let narrow = SplitColumnLayout(total: 700, minimum: 220, detailMinimum: 340, initial: 300)
        XCTAssertEqual(wide.width(fraction: 0.7), 840)
        XCTAssertEqual(narrow.width(fraction: 0.7), 359)
        XCTAssertEqual(wide.width(fraction: 0.7), 840)
        XCTAssertEqual(wide.width(fraction: .nan), 300)
        XCTAssertFalse(SplitColumnLayout(total: 500, minimum: 220, detailMinimum: 340, initial: 300).canResize)
    }
}
