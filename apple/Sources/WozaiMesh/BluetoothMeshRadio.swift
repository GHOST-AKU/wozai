import Foundation
import CoreBluetooth

/// Foreground BLE CoC adapter for the existing public Mesh. Discovery labels confer no trust.
public final class BluetoothMeshRadio: NSObject, CBCentralManagerDelegate, CBPeripheralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    private let queue=DispatchQueue(label:"nearby-mesh-radio")
    private let port: UInt16
    private let label="-" + UUID().uuidString.replacingOccurrences(of:"-",with:"").prefix(6).lowercased()
    private let notice: @Sendable (String) -> Void
    private let service=CBUUID(string:MeshCoCProtocol.service)
    private let characteristic=CBUUID(string:MeshCoCProtocol.characteristic)
    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?
    private var psm: CBL2CAPPSM?
    private var running=false
    private var generation=UUID()
    private var publishing=false
    private var publicationFailures=0
    private var publishWatchdog: DispatchWorkItem?
    private enum Phase: Equatable { case idle, connecting, services, offer, opening, linked, retiring }
    private final class Candidate {
        let device: CBPeripheral
        var offer: MeshCoCProtocol.Offer?
        var advertisedOffer=false
        var phase=Phase.idle
        var passive=false
        var passiveUntil:TimeInterval=0
        var failures=0
        var retryAt: TimeInterval=0
        var seen=ProcessInfo.processInfo.systemUptime
        var timer: DispatchWorkItem?
        init(_ device: CBPeripheral) { self.device=device }
    }
    private struct Session {
        let stream: BLEMeshStream
        let task: Task<Void,Never>
        let peer: UUID?
        let born: TimeInterval
    }
    private var candidates: [UUID:Candidate]=[:]
    private var sessions: [UUID:Session]=[:]
    public init(corePort: UInt16, notice: @escaping @Sendable (String) -> Void) { self.port=corePort;self.notice=notice;super.init() }
    public func start() {
        queue.async { [self] in
            guard !running else { return };running=true;generation=UUID()
            central=CBCentralManager(delegate:self,queue:queue,options:[CBCentralManagerOptionShowPowerAlertKey:false])
            peripheral=CBPeripheralManager(delegate:self,queue:queue,options:[CBPeripheralManagerOptionShowPowerAlertKey:false])
        }
    }
    public func stop() { queue.async { [self] in teardown() } }
    private func teardown() {
        running=false;generation=UUID();publishWatchdog?.cancel();publishWatchdog=nil
        central?.stopScan(); peripheral?.stopAdvertising()
        if let psm { peripheral?.unpublishL2CAPChannel(psm) };psm=nil;publishing=false
        for candidate in candidates.values {
            candidate.timer?.cancel();central?.cancelPeripheralConnection(candidate.device);candidate.device.delegate=nil
        }
        for session in sessions.values { session.task.cancel();session.stream.close() }
        sessions=[:];candidates=[:]
        peripheral?.removeAllServices();central?.delegate=nil;peripheral?.delegate=nil
        central=nil;peripheral=nil
    }
    public func centralManagerDidUpdateState(_ manager: CBCentralManager) {
        guard running,manager === central else { return }
        if manager.state == .poweredOn {
            manager.scanForPeripherals(withServices:[service],options:[CBCentralManagerScanOptionAllowDuplicatesKey:true])
            notice("ble_scanning")
        } else {
            for candidate in candidates.values { candidate.timer?.cancel() }
            for (id,session) in sessions where session.peer != nil { session.task.cancel();session.stream.close();sessions.removeValue(forKey:id) }
            candidates=[:];notice(manager.state == .unauthorized ? "ble_permission_denied" : "ble_unavailable")
        }
    }
    public func peripheralManagerDidUpdateState(_ manager: CBPeripheralManager) {
        guard running,manager === peripheral else { return }
        psm=nil;publishing=false;publishWatchdog?.cancel()
        if manager.state == .poweredOn { publish(manager) }
        else { notice(manager.state == .unauthorized ? "ble_permission_denied" : "ble_unavailable") }
    }
    private func publish(_ manager: CBPeripheralManager) {
        guard running,manager === peripheral,manager.state == .poweredOn,!publishing,psm == nil else { return }
        publishing=true;let token=generation
        manager.publishL2CAPChannel(withEncryption:false)
        let watchdog=DispatchWorkItem { [weak self] in
            guard let self,self.running,self.generation==token,self.psm==nil else { return }
            self.recoverPublication("ble_publish_timeout")
        }
        publishWatchdog=watchdog;queue.asyncAfter(deadline:.now()+20,execute:watchdog)
    }
    public func peripheralManager(_ manager: CBPeripheralManager, didPublishL2CAPChannel value: CBL2CAPPSM, error: Error?) {
        guard running,manager === peripheral else { manager.unpublishL2CAPChannel(value);return }
        publishing=false;publishWatchdog?.cancel()
        guard error == nil,value>0, let offer=try? MeshCoCProtocol.advertisement(label:label,psm:value) else {
            recoverPublication("ble_publish_failed");return
        }
        psm=value
        let info=CBMutableCharacteristic(type:characteristic,properties:[.read],value:offer,permissions:[.readable])
        let published=CBMutableService(type:service,primary:true);published.characteristics=[info]
        manager.add(published)
    }
    public func peripheralManager(_ manager: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard running,manager === peripheral else { return }
        if error != nil { recoverPublication("ble_service_failed");return }
        manager.startAdvertising([CBAdvertisementDataServiceUUIDsKey:[self.service],CBAdvertisementDataLocalNameKey:label])
    }
    public func peripheralManagerDidStartAdvertising(_ manager: CBPeripheralManager, error: Error?) {
        guard running,manager === peripheral else { return }
        if error == nil { publicationFailures=0;notice("ble_ready") }
        else { recoverPublication("ble_advertising_failed") }
    }
    private func recoverPublication(_ reason:String) {
        guard running else { return };notice(reason);publishWatchdog?.cancel()
        let token=generation
        peripheral?.stopAdvertising();if let psm { peripheral?.unpublishL2CAPChannel(psm) }
        peripheral?.removeAllServices();peripheral?.delegate=nil;peripheral=nil;psm=nil;publishing=false
        for (id,session) in sessions where session.peer == nil { session.task.cancel();session.stream.close();sessions.removeValue(forKey:id) }
        publicationFailures=min(publicationFailures+1,6)
        let retry=DispatchWorkItem { [weak self] in
            guard let self,self.running,self.generation==token,self.peripheral==nil else { return }
            self.peripheral=CBPeripheralManager(delegate:self,queue:self.queue,options:[CBPeripheralManagerOptionShowPowerAlertKey:false])
        }
        publishWatchdog=retry;queue.asyncAfter(deadline:.now()+Double(min(60,1 << publicationFailures)),execute:retry)
    }
    public func centralManager(_ manager: CBCentralManager, didDiscover device: CBPeripheral, advertisementData data: [String:Any], rssi RSSI: NSNumber) {
        guard running,manager === central else { return }
        let hint=(data[CBAdvertisementDataServiceDataKey] as? [CBUUID:Data])?[service]
        let offer=hint.flatMap(MeshCoCProtocol.offer)
        if hint != nil && offer == nil { return }
        if let offer,!MeshCoCProtocol.admits(local:label,remote:offer.label,outbound:true) { return }
        if candidates[device.identifier] == nil {
            if candidates.count>=32 {
                guard let victim=candidates.filter({$0.value.phase == .idle}).min(by:{$0.value.seen<$1.value.seen}) else { return }
                victim.value.timer?.cancel();candidates.removeValue(forKey:victim.key)
            }
            candidates[device.identifier]=Candidate(device)
        }
        guard let candidate=candidates[device.identifier] else { return }
        candidate.seen=ProcessInfo.processInfo.systemUptime
        if let offer { candidate.offer=offer;candidate.advertisedOffer=true }
        dial(candidate)
    }
    private func dial(_ candidate: Candidate) {
        let pending=candidates.values.filter { $0.phase != .idle && $0.phase != .linked }.count
        if candidate.passive,ProcessInfo.processInfo.systemUptime>=candidate.passiveUntil { candidate.passive=false }
        guard running,central?.state == .poweredOn,candidate.phase == .idle,!candidate.passive,
              sessions.count+pending<4,pending<2,ProcessInfo.processInfo.systemUptime>=candidate.retryAt else { return }
        candidate.timer?.cancel();candidate.phase = .connecting;candidate.device.delegate=self
        central?.connect(candidate.device,options:nil)
        let token=generation
        let deadline=DispatchWorkItem { [weak self,weak candidate] in
            guard let self,let candidate,self.running,self.generation==token,candidate.phase != .linked else { return }
            self.retire(candidate)
        }
        candidate.timer=deadline;queue.asyncAfter(deadline:.now()+20,execute:deadline)
    }
    private func retire(_ candidate: Candidate) {
        guard candidate.phase != .retiring else { return }
        candidate.phase = .retiring;candidate.timer?.cancel();central?.cancelPeripheralConnection(candidate.device)
        let token=generation
        let watchdog=DispatchWorkItem { [weak self,weak candidate] in
            guard let self,let candidate,self.running,self.generation==token,candidate.phase == .retiring else { return }
            // Cancellation failed to complete: recreate only the central and fence its callbacks.
            self.notice("ble_central_recovery")
            for session in self.sessions.values where session.peer != nil { session.task.cancel();session.stream.close() }
            for peer in self.candidates.values { peer.timer?.cancel();self.central?.cancelPeripheralConnection(peer.device) }
            self.sessions=self.sessions.filter { $0.value.peer == nil };self.candidates=[:]
            self.central?.stopScan();self.central?.delegate=nil
            self.central=CBCentralManager(delegate:self,queue:self.queue,options:[CBCentralManagerOptionShowPowerAlertKey:false])
        }
        candidate.timer=watchdog;queue.asyncAfter(deadline:.now()+15,execute:watchdog)
    }
    public func centralManager(_ manager: CBCentralManager, didConnect device: CBPeripheral) {
        guard running,manager === central,let candidate=candidates[device.identifier],candidate.phase == .connecting else { manager.cancelPeripheralConnection(device);return }
        if candidate.advertisedOffer,let offer=candidate.offer { candidate.phase = .opening;device.openL2CAPChannel(offer.psm) }
        else { candidate.phase = .services;device.discoverServices([service]) }
    }
    public func peripheral(_ device: CBPeripheral, didDiscoverServices error: Error?) {
        guard let c=candidates[device.identifier],c.device === device,c.phase == .services else { return }
        guard error == nil,let service=device.services?.first(where:{$0.uuid==self.service}) else { retire(c);return }
        device.discoverCharacteristics([characteristic],for:service)
    }
    public func peripheral(_ device: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let c=candidates[device.identifier],c.device === device,c.phase == .services else { return }
        guard error == nil,let value=service.characteristics?.first(where:{$0.uuid==characteristic}) else { retire(c);return }
        c.phase = .offer;device.readValue(for:value)
    }
    public func peripheral(_ device: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let c=candidates[device.identifier],c.device === device,c.phase == .offer,characteristic.uuid==self.characteristic else { return }
        guard error == nil,let data=characteristic.value,let offer=MeshCoCProtocol.offer(data) else { retire(c);return }
        c.offer=offer
        guard MeshCoCProtocol.admits(local:label,remote:offer.label,outbound:true) else {
            c.passive=true;c.passiveUntil=ProcessInfo.processInfo.systemUptime+60;retire(c);return
        }
        c.phase = .opening;device.openL2CAPChannel(offer.psm)
    }
    public func peripheral(_ device: CBPeripheral, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard let c=candidates[device.identifier],c.device === device,c.phase == .opening,error == nil,let channel else {
            channel?.inputStream.close();channel?.outputStream.close();if let c=candidates[device.identifier] { retire(c) };return
        }
        c.timer?.cancel();c.phase = .linked
        accept(channel,peer:device.identifier,expected:c.offer?.label,outbound:true)
    }
    public func peripheralManager(_ manager: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard running,manager === peripheral,error == nil,let channel else { channel?.inputStream.close();channel?.outputStream.close();return }
        accept(channel,peer:nil,expected:nil,outbound:false)
    }
    private func accept(_ channel: CBL2CAPChannel, peer: UUID?, expected: String?, outbound: Bool) {
        guard running,sessions.count<4 else { channel.inputStream.close();channel.outputStream.close();if let peer,let c=candidates[peer] { retire(c) };return }
        let stream=BLEMeshStream(input:channel.inputStream,output:channel.outputStream)
        let id=UUID(),token=generation,port=self.port,label=self.label
        let task=Task { [weak self] in
            do { try await MeshBridge.run(radio:stream,corePort:port,label:label,expected:expected,outbound:outbound) } catch { }
            self?.queue.async { [weak self] in
                guard let self,self.generation==token,let session=self.sessions.removeValue(forKey:id) else { return }
                if let peer=session.peer,let candidate=self.candidates[peer] {
                    if ProcessInfo.processInfo.systemUptime-session.born>30 { candidate.failures=0 }
                    self.retire(candidate)
                }
                self.notice("ble_stream_closed")
            }
        }
        sessions[id]=Session(stream:stream,task:task,peer:peer,born:ProcessInfo.processInfo.systemUptime)
    }
    public func centralManager(_ manager: CBCentralManager, didDisconnectPeripheral device: CBPeripheral, error: Error?) { disconnected(manager,device) }
    public func centralManager(_ manager: CBCentralManager, didFailToConnect device: CBPeripheral, error: Error?) { disconnected(manager,device) }
    private func disconnected(_ manager: CBCentralManager, _ device: CBPeripheral) {
        guard running,manager === central,let candidate=candidates[device.identifier] else { return }
        candidate.timer?.cancel();candidate.phase = .idle
        for (id,session) in sessions where session.peer==device.identifier { session.task.cancel();session.stream.close();sessions.removeValue(forKey:id) }
        candidate.failures=min(candidate.failures+1,6)
        let delay=max(candidate.passiveUntil-ProcessInfo.processInfo.systemUptime,Double(min(60,1 << candidate.failures))) + Double.random(in:0...0.5)
        candidate.retryAt=ProcessInfo.processInfo.systemUptime+delay
        let token=generation
        let retry=DispatchWorkItem { [weak self,weak candidate] in
            guard let self,let candidate,self.running,self.generation==token else { return };self.dial(candidate)
        }
        candidate.timer=retry;queue.asyncAfter(deadline:.now()+delay,execute:retry)
    }
}
