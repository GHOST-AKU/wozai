import Foundation
import XCTest
@testable import WozaiCore

final class AttachmentRecordTests: XCTestCase {
    private let id = "11111111-1111-4111-8111-111111111111"
    private let source = "22222222-2222-4222-8222-222222222222"
    private let generation = String(repeating: "a", count: 64)

    func testAttachmentFrameRejectsInvalidInnerWireBeforeDispatch() throws {
        for packet in [packet(kind: 2, sender: false, payload: Data([1])),
                       packet(kind: 2, payload: Data()),
                       packet(kind: 2, offset: 4, payload: Data([1])),
                       packet(kind: 4, sender: false, window: 9 * 1024 * 1024),
                       packet(kind: 3, offset: 3),
                       packet(kind: 5, offset: 2, hash: Data(repeating: 0, count: 32)),
                       packet(kind: 10, reason: "paused")] {
            let frame = NIMFrame(type: .attachment, binary: packet)
            XCTAssertThrowsError(try frame.encoded())
            var wire = WireWriter(); wire.u32(0x4e494d34); wire.byte(4); wire.byte(16)
            wire.u64(0); wire.text(""); wire.text(""); wire.u32(UInt32(packet.count)); wire.data += packet
            XCTAssertThrowsError(try NIMFrame.decode(wire.data))
        }
    }

    func testLegalControlsRemainRoundTrippable() throws {
        for binary in [packet(kind: 2, payload: Data([1, 2, 3])),
                       packet(kind: 3, offset: 3, hash: Data(repeating: 0, count: 32)),
                       packet(kind: 4, sender: false, offset: 3, window: 1024 * 1024),
                       packet(kind: 7, reason: ""), packet(kind: 9), packet(kind: 10)] {
            let frame = NIMFrame(type: .attachment, binary: binary)
            XCTAssertEqual(try NIMFrame.decode(frame.encoded()), frame)
        }
    }

    private func packet(kind: UInt8, sender: Bool = true, offset: UInt64 = 0,
                        window: UInt64 = 0, reason: String = "", hash: Data? = nil,
                        payload: Data = Data()) -> Data {
        var body = WireWriter(); body.u32(0x46543033); body.byte(kind); body.byte(sender ? 1 : 0)
        body.text(id); body.text(source); body.text(generation); body.u64(3); body.u64(offset)
        body.u64(window); body.u64(0); body.u64(0); body.text(reason); body.byte(0)
        body.byte(hash == nil ? 0 : 1); if let hash { body.data += hash }
        body.u32(UInt32(payload.count)); body.data += payload
        var framed = WireWriter(); framed.u32(UInt32(body.data.count)); framed.data += body.data
        return framed.data
    }
}
