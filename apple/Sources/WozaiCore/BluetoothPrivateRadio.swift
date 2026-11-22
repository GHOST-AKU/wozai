import Foundation
@preconcurrency import CoreBluetooth

public struct BLEPrivatePeer: Identifiable, Sendable {
    public let id: UUID
    public let name: String
}

/// Foreground private-chat radio. All CoreBluetooth objects are confined to this queue.
public final class BluetoothPrivateRadio: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate,
    CBPeripheralDelegate, @unchecked Sendable {
    private let queue = DispatchQueue(label: "dev.ghost.wozai.private-ble")
    private let peers: @Sendable ([BLEPrivatePeer]) -> Void
    private let incoming: @Sendable (BLEByteStream) -> Void
    private let status: @Sendable (String) -> Void
    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?
    private let serviceID = CBUUID(nsuuid: PrivateBLE.service)
    private let rxID = CBUUID(nsuuid: PrivateBLE.receive)
    private let txID = CBUUID(nsuuid: PrivateBLE.transmit)
    private var enabled = false
    private var name = "NearbyIM"
    private var candidates: [UUID: (CBPeripheral, String)] = [:]
    private var published: CBMutableService?
    private var localRX: CBMutableCharacteristic?
    private var localTX: CBMutableCharacteristic?
    private var remote: CBPeripheral?
    private var remoteRX: CBCharacteristic?
    private var remoteTX: CBCharacteristic?
    private var subscriber: CBCentral?
    private var stream: BLEByteStream?
    private var generation = UUID()
    private var setup: CheckedContinuation<BLEByteStream, Error>?
    private var write: (UUID, Data, CheckedContinuation<Void, Error>)?
    private var writeTimer: DispatchSourceTimer?

    public init(peers: @escaping @Sendable ([BLEPrivatePeer]) -> Void,
                incoming: @escaping @Sendable (BLEByteStream) -> Void,
                status: @escaping @Sendable (String) -> Void) {
        self.peers = peers; self.incoming = incoming; self.status = status
    }

    public func start(name: String) { queue.async { [self] in
        guard !enabled else { return }; enabled = true; self.name = String(name.prefix(16))
        central = CBCentralManager(delegate: self, queue: queue)
        peripheral = CBPeripheralManager(delegate: self, queue: queue)
    } }

    public func stop() { queue.async { [self] in
        enabled = false; endLink(); central?.stopScan(); peripheral?.stopAdvertising(); peripheral?.removeAllServices()
        central?.delegate = nil; peripheral?.delegate = nil; central = nil; peripheral = nil
        localRX = nil; localTX = nil; published = nil; candidates.removeAll(); peers([]); status("stopped")
    } }

    public func connect(_ id: UUID) async throws -> BLEByteStream {
        let cancellation = BLEConnectCancellation(), attempt = UUID()
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                queue.async { [self] in
                    guard !cancellation.isCancelled, enabled, central?.state == .poweredOn, stream == nil, setup == nil,
                          let peer = candidates[id]?.0 else { continuation.resume(throwing: WozaiError.closed); return }
                    generation = attempt
                    setup = continuation; remote = peer; peer.delegate = self
                    central?.stopScan(); peripheral?.stopAdvertising(); central?.connect(peer)
                    queue.asyncAfter(deadline: .now() + 20) { [weak self] in
                        guard let self, self.generation == attempt, self.setup != nil else { return }
                        self.endLink(error: WozaiError.timeout)
                    }
                }
            }
        } onCancel: {
            cancellation.cancel()
            self.queue.async { [self] in if generation == attempt { endLink() } }
        }
    }

    private func resumeDiscovery() {
        guard enabled, stream == nil, setup == nil else { return }
        if central?.state == .poweredOn { central?.scanForPeripherals(withServices: [serviceID]) }
        if peripheral?.state == .poweredOn, published != nil {
            peripheral?.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [serviceID], CBAdvertisementDataLocalNameKey: name])
        }
    }

    public func centralManagerDidUpdateState(_ manager: CBCentralManager) {
        guard manager === central else { return }
        if manager.state == .poweredOn { resumeDiscovery(); status("ready") }
        else { endLink(); candidates.removeAll(); peers([]); status(manager.state == .unauthorized ? "permission" : "unavailable") }
    }
    public func peripheralManagerDidUpdateState(_ manager: CBPeripheralManager) {
        guard manager === peripheral else { return }
        guard manager.state == .poweredOn else { published = nil; localRX = nil; localTX = nil; endLink(); return }
        let rx = CBMutableCharacteristic(type: rxID, properties: [.write], value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: txID, properties: [.indicate], value: nil, permissions: [.readable])
        let service = CBMutableService(type: serviceID, primary: true); service.characteristics = [rx, tx]
        localRX = rx; localTX = tx; manager.add(service)
    }
    public func peripheralManager(_ manager: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard manager === peripheral, enabled else { return }
        guard error == nil, let service = service as? CBMutableService else { status("setup_failed"); return }
        published = service; resumeDiscovery()
    }
    public func peripheralManagerDidStartAdvertising(_ manager: CBPeripheralManager, error: Error?) {
        if manager === peripheral, error != nil { status("advertise_failed") }
    }
    public func centralManager(_ manager: CBCentralManager, didDiscover peer: CBPeripheral,
                               advertisementData: [String: Any], rssi: NSNumber) {
        guard manager === central, enabled, stream == nil else { return }
        guard candidates[peer.identifier] != nil || candidates.count < 32 else { return }
        let label = String((advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peer.name ?? "NearbyIM").prefix(64))
        guard candidates[peer.identifier]?.1 != label else { return }
        candidates[peer.identifier] = (peer, label)
        peers(candidates.map { BLEPrivatePeer(id: $0.key, name: $0.value.1) }.sorted { $0.name < $1.name })
    }
    public func centralManager(_ manager: CBCentralManager, didConnect peer: CBPeripheral) {
        guard manager === central, peer === remote, setup != nil else { manager.cancelPeripheralConnection(peer); return }
        peer.discoverServices([serviceID])
    }
    public func centralManager(_ manager: CBCentralManager, didFailToConnect peer: CBPeripheral, error: Error?) {
        if manager === central, peer === remote { endLink() }
    }
    public func centralManager(_ manager: CBCentralManager, didDisconnectPeripheral peer: CBPeripheral, error: Error?) {
        if manager === central, peer === remote { endLink() }
    }
    public func peripheral(_ peer: CBPeripheral, didDiscoverServices error: Error?) {
        guard peer === remote, setup != nil else { return }
        guard error == nil, let service = peer.services?.first(where: { $0.uuid == serviceID }) else { endLink(); return }
        peer.discoverCharacteristics([rxID, txID], for: service)
    }
    public func peripheral(_ peer: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard peer === remote, setup != nil else { return }
        guard error == nil,
              let rx = service.characteristics?.first(where: { $0.uuid == rxID && $0.properties.contains(.write) }),
              let tx = service.characteristics?.first(where: { $0.uuid == txID && $0.properties.contains(.indicate) }) else { endLink(); return }
        remoteRX = rx; remoteTX = tx; peer.setNotifyValue(true, for: tx)
    }
    public func peripheral(_ peer: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard peer === remote, characteristic === remoteTX else { return }
        guard error == nil, characteristic.isNotifying else { endLink(); return }
        guard setup != nil else { return }
        let connected = makeStream(valueSize: peer.maximumWriteValueLength(for: .withResponse))
        let pending = setup; setup = nil; pending?.resume(returning: connected)
    }
    public func peripheral(_ peer: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peer === remote, characteristic === remoteTX else { return }
        guard error == nil, let value = characteristic.value, stream?.receive(value) == true else { endLink(); return }
    }
    public func peripheral(_ peer: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard peer === remote, characteristic === remoteRX, let pending = write else { return }
        write = nil; writeTimer?.cancel(); writeTimer = nil
        if let error { pending.2.resume(throwing: error); endLink() } else { pending.2.resume() }
    }
    public func peripheralManager(_ manager: CBPeripheralManager, central peer: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard manager === peripheral, characteristic === localTX, enabled, stream == nil, setup == nil else { return }
        subscriber = peer; generation = UUID(); central?.stopScan(); manager.stopAdvertising()
        incoming(makeStream(valueSize: peer.maximumUpdateValueLength))
    }
    public func peripheralManager(_ manager: CBPeripheralManager, central peer: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        if manager === peripheral, peer.identifier == subscriber?.identifier, characteristic === localTX { endLink() }
    }
    public func peripheralManager(_ manager: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard manager === peripheral, let first = requests.first else { return }
        // Long/prepared writes and multi-attribute transactions are not this byte transport.
        guard requests.count == 1, first.characteristic.uuid == rxID, first.offset == 0,
              first.central.identifier == subscriber?.identifier, let value = first.value, stream != nil else {
            manager.respond(to: first, withResult: .requestNotSupported); return
        }
        let accepted = stream?.receive(value) == true
        manager.respond(to: first, withResult: accepted ? .success : .insufficientResources)
    }
    public func peripheralManagerIsReady(toUpdateSubscribers manager: CBPeripheralManager) {
        guard manager === peripheral else { return }; flushIndication()
    }

    private func makeStream(valueSize: Int) -> BLEByteStream {
        let epoch = generation
        let result = BLEByteStream(valueSize: valueSize, send: { [weak self] value in
            guard let self else { throw WozaiError.closed }
            try await self.send(value, epoch: epoch)
        }, closed: { [weak self] in
            self?.queue.async { [weak self] in
                guard let self, self.generation == epoch else { return }; self.endLink()
            }
        })
        stream = result; return result
    }
    private func send(_ value: Data, epoch: UUID) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async { [self] in
                guard generation == epoch, stream != nil, write == nil else { continuation.resume(throwing: WozaiError.closed); return }
                let operation = UUID(); write = (operation, value, continuation)
                let timer = DispatchSource.makeTimerSource(queue: queue)
                writeTimer = timer; timer.schedule(deadline: .now() + 10)
                timer.setEventHandler { [weak self] in
                    guard let self, self.write?.0 == operation else { return }; self.endLink(error: WozaiError.timeout)
                }
                timer.resume()
                if let peer = remote, let rx = remoteRX { peer.writeValue(value, for: rx, type: .withResponse) }
                else { flushIndication() }
            }
        }
    }
    private func flushIndication() {
        guard let pending = write, let subscriber, let tx = localTX,
              peripheral?.updateValue(pending.1, for: tx, onSubscribedCentrals: [subscriber]) == true else { return }
        write = nil; writeTimer?.cancel(); writeTimer = nil; pending.2.resume()
    }
    private func endLink(error: Error = WozaiError.closed) {
        generation = UUID()
        let old = stream; stream = nil
        let connecting = setup; setup = nil
        let pending = write; write = nil; writeTimer?.cancel(); writeTimer = nil
        if let remote {
            remote.delegate = nil; central?.stopScan(); central?.delegate = nil; central?.cancelPeripheralConnection(remote)
            // A new manager fences late disconnect/write callbacks when reconnecting the same peripheral.
            candidates.removeAll(); peers([])
            central = enabled ? CBCentralManager(delegate: self, queue: queue) : nil
        }
        if let subscriber, let tx = localTX { _ = peripheral?.updateValue(Data([1]), for: tx, onSubscribedCentrals: [subscriber]) }
        remote = nil; remoteRX = nil; remoteTX = nil; subscriber = nil
        old?.close(); connecting?.resume(throwing: error); pending?.2.resume(throwing: error)
        resumeDiscovery()
    }
}

private final class BLEConnectCancellation: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    var isCancelled: Bool { lock.withLock { cancelled } }
    func cancel() { lock.withLock { cancelled = true } }
}
