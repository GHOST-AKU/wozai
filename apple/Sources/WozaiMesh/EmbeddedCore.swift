import Foundation
import CMeshBridge

public struct PublicMeshEvent: Decodable, Sendable {
    public let event: String
    public let key: String?
    public let address: String?
    public let relay: Bool?
    public let enabled: Bool?
    public let id: String?
    public let body: String?
    public let path: [String]?
    public let peer: String?
    public let reason: String?
    public let request: String?
    public var loopbackPort: UInt16? {
        guard let address, address.hasPrefix("127.0.0.1:"), let value=UInt16(address.dropFirst(10)),value>0 else { return nil }
        return value
    }
}
/// Synchronous FFI operations belong on a worker, not the MainActor.
public final class EmbeddedMeshCore: @unchecked Sendable {
    public static var available: Bool { wm_available() == 1 }
    private let handle: UInt64
    private let lock=NSLock()
    private let polling=DispatchQueue(label:"nearby-mesh-events")
    private var stopped=false
    public init(directory: URL) throws {
        guard Self.available else { throw MeshError.unavailable }
        let bytes=Array(directory.path.utf8)
        handle=bytes.withUnsafeBufferPointer { wm_start($0.baseAddress,$0.count) }
        guard handle != 0 else { throw MeshError.coreFailed }
    }
    public func nextEvent() throws -> PublicMeshEvent? {
        var bytes=[UInt8](repeating:0,count:16384)
        let size=bytes.withUnsafeMutableBufferPointer { wm_poll(handle,$0.baseAddress,$0.count,250) }
        if size == 0 { return nil }
        guard size > 0 else { throw MeshError.coreFailed }
        return try JSONDecoder().decode(PublicMeshEvent.self,from:Data(bytes.prefix(Int(size))))
    }
    public func nextEventAsync() async throws -> PublicMeshEvent? {
        try await withCheckedThrowingContinuation { continuation in
            polling.async { [self] in
                do { continuation.resume(returning:try nextEvent()) }
                catch { continuation.resume(throwing:error) }
            }
        }
    }
    public func command(_ value: [String: any Sendable]) throws {
        let bytes=try JSONSerialization.data(withJSONObject:value)
        guard bytes.count<=16384 else { throw MeshError.bound }
        let accepted=bytes.withUnsafeBytes { wm_command(handle,$0.bindMemory(to:UInt8.self).baseAddress,bytes.count) }
        guard accepted == 1 else { throw MeshError.busy }
    }
    public func close() {
        lock.lock(); let first = !stopped; stopped=true; lock.unlock()
        if first { wm_stop(handle) }
    }
    deinit { close() }
}
