import Foundation
import Network

final class MeshTCPStream: MeshByteStream, @unchecked Sendable {
    private let connection: NWConnection
    private init(port: UInt16) {
        let options=NWProtocolTCP.Options(); options.noDelay=true
        let parameters=NWParameters(tls:nil,tcp:options)
        connection=NWConnection(host:"127.0.0.1",port:NWEndpoint.Port(rawValue:port)!,using:parameters)
    }
    static func connect(port: UInt16) async throws -> MeshTCPStream {
        let stream=MeshTCPStream(port:port)
        do {
            try await meshTimeout(5) { try await stream.start() }
            return stream
        } catch { stream.close(); throw error }
    }
    private func start() async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void,Error>) in
                let gate=StartGate(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: gate.finish(nil)
                    case .failed(_), .cancelled: gate.finish(MeshError.closed)
                    default: break
                    }
                }
                connection.start(queue:DispatchQueue(label:"mesh-core-stream"))
            }
        } onCancel: { self.close() }
    }
    func read(maximum: Int) async throws -> Data {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength:1,maximumLength:maximum) { data,_,complete,error in
                    if error != nil { continuation.resume(throwing:MeshError.closed) }
                    else if let data,!data.isEmpty { continuation.resume(returning:data) }
                    else if complete { continuation.resume(returning:Data()) }
                    else { continuation.resume(throwing:MeshError.closed) }
                }
            }
        } onCancel: { self.close() }
    }
    func write(_ data: Data) async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void,Error>) in
                connection.send(content:data,completion:.contentProcessed { error in
                    if error == nil { continuation.resume() } else { continuation.resume(throwing:MeshError.closed) }
                })
            }
        } onCancel: { self.close() }
    }
    func close() { connection.cancel() }
    private final class StartGate: @unchecked Sendable {
        let lock=NSLock(); var continuation: CheckedContinuation<Void,Error>?
        init(_ continuation: CheckedContinuation<Void,Error>) { self.continuation=continuation }
        func finish(_ error: Error?) {
            lock.lock();let callback=continuation;continuation=nil;lock.unlock()
            if let error { callback?.resume(throwing:error) } else { callback?.resume() }
        }
    }
}

enum MeshBridge {
    static func pump(_ from: any MeshByteStream, _ to: any MeshByteStream) async throws {
        while !Task.isCancelled {
            let bytes=try await from.read(maximum:4096)
            if bytes.isEmpty { return }
            try await meshTimeout(5) { try await to.write(bytes) }
        }
    }
    static func run(radio: any MeshByteStream, corePort: UInt16, label: String, expected: String?, outbound: Bool) async throws {
        defer { radio.close() }
        _ = try await meshTimeout(10) { try await MeshCoCProtocol.handshake(radio,local:label,expected:expected,outbound:outbound) }
        let core=try await MeshTCPStream.connect(port:corePort)
        defer { core.close() }
        try await withThrowingTaskGroup(of:Void.self) { group in
            group.addTask { try await pump(radio,core) }
            group.addTask { try await pump(core,radio) }
            defer { radio.close(); core.close(); group.cancelAll() }
            _ = try await group.next()
        }
    }
}
