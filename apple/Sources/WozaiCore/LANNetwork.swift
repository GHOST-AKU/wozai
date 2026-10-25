import Foundation
import Network
import Darwin

public enum LocalEndpoint {
    public static func allows(_ host: String) -> Bool {
        if let ip = IPv4Address(host) {
            return localIPv4([UInt8](ip.rawValue))
        }
        let unscoped = String(host.split(separator: "%", maxSplits: 1).first ?? "")
        if let ip = IPv6Address(unscoped) {
            let b = [UInt8](ip.rawValue)
            if Array(b.prefix(12)) == Array(repeating: UInt8(0), count: 10) + [255, 255] { return localIPv4(Array(b.suffix(4))) }
            return b[0] & 0xfe == 0xfc || (b[0] == 0xfe && b[1] & 0xc0 == 0x80) || b == Array(repeating: 0, count: 15) + [1]
        }
        let name = host.hasSuffix(".") ? String(host.dropLast()) : host
        guard name.utf8.count <= 253, name.lowercased().hasSuffix(".local") else { return false }
        return name.split(separator: ".", omittingEmptySubsequences: false).allSatisfy { label in
            !label.isEmpty && label.utf8.count <= 63 && label.first != "-" && label.last != "-" &&
            label.utf8.allSatisfy { (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 }
        }
    }

    private static func localIPv4(_ b: [UInt8]) -> Bool {
        b[0] == 10 || b[0] == 127 || (b[0] == 172 && (16...31).contains(b[1])) || (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254)
    }

    public static func addresses(port: UInt16) -> [String] {
        var first: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&first) == 0 else { return [] }
        defer { freeifaddrs(first) }
        var values = Set<String>(), current = first
        while let entry = current {
            defer { current = entry.pointee.ifa_next }
            guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) || address.pointee.sa_family == UInt8(AF_INET6) else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(address, socklen_t(address.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let host = String(cString: buffer)
            guard allows(host), !host.hasPrefix("127."), host != "::1" else { continue }
            values.insert(host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)")
        }
        return values.sorted()
    }
}

func localParameters() -> NWParameters {
    let tcp = NWProtocolTCP.Options()
    tcp.noDelay = true
    tcp.enableKeepalive = true
    let parameters = NWParameters(tls: nil, tcp: tcp)
    parameters.includePeerToPeer = false
    parameters.prohibitedInterfaceTypes = [.cellular]
    return parameters
}

public final class NWByteStream: ByteStream, @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "dev.ghost.wozai.socket", qos: .userInitiated)

    public init(connection: NWConnection) { self.connection = connection }

    public convenience init(host: String, port: UInt16) throws {
        guard LocalEndpoint.allows(host), let port = NWEndpoint.Port(rawValue: port), port.rawValue != 0 else { throw WozaiError.protocolViolation }
        self.init(connection: NWConnection(host: NWEndpoint.Host(host), port: port, using: localParameters()))
    }

    public convenience init(endpoint: NWEndpoint) { self.init(connection: NWConnection(to: endpoint, using: localParameters())) }

    public func start() async throws {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                let gate = StartGate(continuation)
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready: gate.finish(.success(()))
                    case .failed(let error): gate.finish(.failure(error))
                    case .cancelled: gate.finish(.failure(WozaiError.closed))
                    default: break
                    }
                }
                connection.start(queue: queue)
                queue.asyncAfter(deadline: .now() + 8) { [connection] in
                    if gate.finish(.failure(WozaiError.timeout)) { connection.cancel() }
                }
            }
        } onCancel: { self.close() }
    }

    public func readExactly(_ count: Int) async throws -> Data {
        guard count >= 0, count <= 48 * 1024 + 16 else { throw WozaiError.protocolViolation }
        var result = Data()
        while result.count < count {
            let remaining = count - result.count
            let chunk = try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
                    connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { data, _, complete, error in
                        if let error { continuation.resume(throwing: error) }
                        else if let data, !data.isEmpty { continuation.resume(returning: data) }
                        else { continuation.resume(throwing: complete ? WozaiError.closed : WozaiError.protocolViolation) }
                    }
                }
            } onCancel: { self.close() }
            result += chunk
        }
        return result
    }

    public func write(_ data: Data) async throws {
        guard data.count <= 48 * 1024 + 26 else { throw WozaiError.protocolViolation }
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                connection.send(content: data, completion: .contentProcessed { error in
                    if let error { continuation.resume(throwing: error) } else { continuation.resume() }
                })
            }
        } onCancel: { self.close() }
    }

    public func close() { connection.cancel() }
}

private final class StartGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    init(_ continuation: CheckedContinuation<Void, Error>) { self.continuation = continuation }
    @discardableResult func finish(_ result: Result<Void, Error>) -> Bool {
        lock.lock(); let pending = continuation; continuation = nil; lock.unlock()
        pending?.resume(with: result)
        return pending != nil
    }
}

public struct DiscoveredPeer: Identifiable, Sendable {
    public let id: String
    public let nickname: String
    public let claimedID: String?
    public let endpoint: NWEndpoint
}

public final class LANDiscovery: @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.ghost.wozai.discovery", qos: .utility)
    private var listener: NWListener?
    private var browser: NWBrowser?
    private var generation: UInt64 = 0
    private let peers: @Sendable ([DiscoveredPeer]) -> Void
    private let incoming: @Sendable (NWByteStream) -> Void
    private let state: @Sendable ([String], String?) -> Void

    public init(peers: @escaping @Sendable ([DiscoveredPeer]) -> Void,
                incoming: @escaping @Sendable (NWByteStream) -> Void,
                state: @escaping @Sendable ([String], String?) -> Void) {
        self.peers = peers; self.incoming = incoming; self.state = state
    }

    public func start(id: String, nickname: String) {
        queue.async { [self] in
            stopOnQueue()
            let epoch = generation
            do {
                let listener = try NWListener(using: localParameters())
                listener.service = NWListener.Service(name: "nearby-" + String(id.prefix(8)), type: "_nearbyim._tcp", domain: "local.", txtRecord: NWTXTRecord(["id": id, "name": nickname]))
                listener.newConnectionHandler = { [weak self] connection in
                    guard let self, self.generation == epoch else { connection.cancel(); return }
                    if case .hostPort(let host, _) = connection.endpoint, !LocalEndpoint.allows(String(describing: host)) { connection.cancel(); return }
                    self.incoming(NWByteStream(connection: connection))
                }
                listener.stateUpdateHandler = { [weak self, weak listener] value in
                    guard let self, self.generation == epoch else { return }
                    switch value {
                    case .ready:
                        if let port = listener?.port { self.state(LocalEndpoint.addresses(port: port.rawValue), nil) }
                    case .failed(let error), .waiting(let error): self.state([], error.localizedDescription)
                    default: break
                    }
                }
                self.listener = listener
                listener.start(queue: queue)
                // TXT metadata supplies nicknames and self-filtering, not authenticated identity.
                let browser = NWBrowser(for: .bonjourWithTXTRecord(type: "_nearbyim._tcp", domain: "local."), using: localParameters())
                browser.browseResultsChangedHandler = { [weak self] results, _ in
                    guard let self, self.generation == epoch else { return }
                    let found = results.compactMap { result -> DiscoveredPeer? in
                        guard case .service(let name, let type, let domain, _) = result.endpoint else { return nil }
                        var nickname = name, remoteID: String?
                        if case .bonjour(let txt) = result.metadata {
                            if let raw = txt["id"], validID(raw), raw != id { remoteID = raw } else if txt["id"] == id { return nil }
                            if let raw = txt["name"], raw.utf8.count <= 128 { nickname = raw }
                        }
                        return DiscoveredPeer(id: name + "." + type + "." + domain, nickname: nickname, claimedID: remoteID, endpoint: result.endpoint)
                    }.sorted { $0.id < $1.id }
                    self.peers(Array(found.prefix(256)))
                }
                browser.stateUpdateHandler = { [weak self] value in
                    guard let self, self.generation == epoch else { return }
                    if case .failed(let error) = value { self.state([], error.localizedDescription) }
                    if case .waiting(let error) = value { self.state([], error.localizedDescription) }
                }
                self.browser = browser
                browser.start(queue: queue)
            } catch { state([], error.localizedDescription) }
        }
    }

    public func stop() { queue.async { [self] in stopOnQueue(); state([], nil) } }
    private func stopOnQueue() { generation &+= 1; browser?.cancel(); listener?.cancel(); browser = nil; listener = nil; peers([]) }
    deinit { browser?.cancel(); listener?.cancel() }
}
