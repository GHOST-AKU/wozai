import Foundation
import XCTest
@testable import WozaiMesh

final class CoCProtocolTests: XCTestCase {
    func testAndroidAdvertisementVectorAndBounds() throws {
        // Independently specified by existing Java CocProtocol v1: label abc, PSM 0x0081.
        let bytes = Data([1,3,97,98,99,0,129])
        let offer = try XCTUnwrap(MeshCoCProtocol.offer(bytes))
        XCTAssertEqual(offer.label, "abc"); XCTAssertEqual(offer.psm, 129)
        XCTAssertEqual(try MeshCoCProtocol.advertisement(label: "abc", psm: 129), bytes)
        for bad in [Data(), Data([2,3,97,98,99,0,129]), Data([1,1,255,0,129]), Data([1,1,97,0,0]), bytes + Data([0])] {
            XCTAssertNil(MeshCoCProtocol.offer(bad))
        }
        XCTAssertThrowsError(try MeshCoCProtocol.advertisement(label: "123456789", psm: 129))
    }
    func testPrefaceDoesNotConsumeFollowingCoreBytesAndChecksExpectedRole() throws {
        let header = Data([78,77,76,67,1,3])
        XCTAssertEqual(try MeshCoCProtocol.labelLength(header), 3)
        XCTAssertEqual(try MeshCoCProtocol.preface("abc"), header + Data("abc".utf8))
        XCTAssertThrowsError(try MeshCoCProtocol.labelLength(Data([78,77,76,67,1,0])))
        XCTAssertTrue(MeshCoCProtocol.admits(local: "-123456", remote: "abc", outbound: true))
        XCTAssertFalse(MeshCoCProtocol.admits(local: "abc", remote: "-123456", outbound: true))
        XCTAssertFalse(MeshCoCProtocol.admits(local: "same", remote: "same", outbound: false))
    }
}
