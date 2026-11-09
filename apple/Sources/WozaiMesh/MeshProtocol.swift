import Foundation

public enum MeshError: String, Error, Sendable {
    case closed, bound, invalidPreface, role, timeout, unavailable, coreFailed, busy
}
public protocol MeshByteStream: Sendable {
    func read(maximum: Int) async throws -> Data
    func write(_ data: Data) async throws
    func close()
}
func meshExact(_ stream: any MeshByteStream, _ count: Int) async throws -> Data {
    var result = Data()
    while result.count < count {
        let part = try await stream.read(maximum: count-result.count)
        guard !part.isEmpty else { throw MeshError.closed }
        result.append(part)
    }
    return result
}
enum MeshCoCProtocol {
    static let service = "eed3c891-5c83-4f9c-a88d-94bc822c71e0"
    // Readable offer characteristic for Apple advertisers, which cannot advertise Service Data.
    static let characteristic = "e22630c6-9f5f-4984-a8d3-65924f1648bc"
    struct Offer { let label: String; let psm: UInt16 }
    static func valid(_ label: String) -> Bool {
        (1...8).contains(label.utf8.count) && label.utf8.allSatisfy {
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95
        }
    }
    static func advertisement(label: String, psm: UInt16) throws -> Data {
        guard valid(label), psm > 0 else { throw MeshError.bound }
        return Data([1,UInt8(label.utf8.count)]) + Data(label.utf8) + Data([UInt8(psm >> 8),UInt8(psm & 255)])
    }
    static func offer(_ bytes: Data) -> Offer? {
        guard (5...12).contains(bytes.count) else { return nil }
        let b = [UInt8](bytes)
        guard (5...12).contains(b.count), b[0] == 1, (1...8).contains(b[1]), b.count == Int(b[1])+4,
              let label=String(bytes:b[2..<(2+Int(b[1]))],encoding:.ascii), valid(label) else { return nil }
        let psm=UInt16(b[b.count-2]) << 8 | UInt16(b[b.count-1])
        return psm == 0 ? nil : Offer(label:label,psm:psm)
    }
    static func preface(_ label: String) throws -> Data {
        guard valid(label) else { throw MeshError.bound }
        return Data([78,77,76,67,1,UInt8(label.utf8.count)]) + Data(label.utf8)
    }
    static func labelLength(_ header: Data) throws -> Int {
        guard header.count == 6 else { throw MeshError.invalidPreface }
        let b=[UInt8](header)
        guard b.count == 6, b[0...4].elementsEqual([78,77,76,67,1]), (1...8).contains(b[5]) else { throw MeshError.invalidPreface }
        return Int(b[5])
    }
    static func admits(local: String, remote: String, outbound: Bool) -> Bool {
        valid(local) && valid(remote) && (outbound ? local < remote : remote < local)
    }
    static func handshake(_ stream: any MeshByteStream, local: String, expected: String?, outbound: Bool) async throws -> String {
        try await stream.write(preface(local))
        let count=try labelLength(await meshExact(stream,6))
        let name=try await meshExact(stream,count)
        guard let remote=String(data:name,encoding:.ascii), admits(local:local,remote:remote,outbound:outbound),
              expected == nil || expected == remote else { throw MeshError.role }
        try await stream.write(Data([82]))
        guard try await meshExact(stream,1) == Data([82]) else { throw MeshError.invalidPreface }
        return remote
    }
}

func meshTimeout<T: Sendable>(_ seconds: Double, _ action: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of:T.self) { group in
        group.addTask { try await action() }
        group.addTask { try await Task.sleep(for:.seconds(seconds)); throw MeshError.timeout }
        defer { group.cancelAll() }
        return try await group.next()!
    }
}
