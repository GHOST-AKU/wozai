import Foundation
import Network

/// Public Network.framework path evidence does not identify AWDL simply by being Wi-Fi.
public struct LocalPathSnapshot: Equatable, Sendable {
    public enum Kind: String, Sendable { case unavailable, wifi, ethernet, loopback, other }
    public let kind: Kind
    public let peerToPeerEnabled: Bool
    public let interfaces: [String]
    public let expensive: Bool
    public let constrained: Bool
    public var directConfirmed: Bool { false }
    public static let unavailable = LocalPathSnapshot(kind: .unavailable, peerToPeerEnabled: true,
                                                      interfaces: [], expensive: false, constrained: false)

    init(path: NWPath?, peerToPeerEnabled: Bool) {
        self.peerToPeerEnabled = peerToPeerEnabled
        interfaces = path?.availableInterfaces.map(\.name).sorted() ?? []
        expensive = path?.isExpensive ?? false
        constrained = path?.isConstrained ?? false
        guard let path, path.status == .satisfied else { kind = .unavailable; return }
        if path.usesInterfaceType(.loopback) { kind = .loopback }
        else if path.usesInterfaceType(.wifi) && !path.usesInterfaceType(.wiredEthernet) { kind = .wifi }
        else if path.usesInterfaceType(.wiredEthernet) && !path.usesInterfaceType(.wifi) { kind = .ethernet }
        else { kind = .other }
    }

    private init(kind: Kind, peerToPeerEnabled: Bool, interfaces: [String], expensive: Bool, constrained: Bool) {
        self.kind = kind; self.peerToPeerEnabled = peerToPeerEnabled
        self.interfaces = interfaces; self.expensive = expensive; self.constrained = constrained
    }

    public var diagnostics: String {
        "apple_network_schema=1\ntransport=local\nfile_transport=\(kind.rawValue)\n" +
        "apple_peer_to_peer_enabled=\(peerToPeerEnabled)\nfile_direct_confirmed=false\n" +
        "file_direct_outcome=\(peerToPeerEnabled ? "system_managed" : "not_requested")\n" +
        "apple_path_interfaces_available=\(interfaces.joined(separator: ","))\n" +
        "apple_path_expensive=\(expensive)\napple_path_constrained=\(constrained)\n"
    }
}
