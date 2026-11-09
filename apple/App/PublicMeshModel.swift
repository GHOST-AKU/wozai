import Foundation
import Observation
import WozaiMesh

@MainActor @Observable
final class PublicMeshModel {
    struct Message: Identifiable {
        let id: String
        let body: String
        let own: Bool
        let hops: Int
        var state: String
    }
    var starting=false
    var running=false
    var relay=false
    var neighbors=Set<String>()
    var messages:[Message]=[]
    var draft=""
    var failed=false
    var radioCode=""
    @ObservationIgnored private var core:EmbeddedMeshCore?
    @ObservationIgnored private var radio:BluetoothMeshRadio?
    @ObservationIgnored private var reader:Task<Void,Never>?
    @ObservationIgnored private var cleanup:Task<Void,Never>?
    @ObservationIgnored private var generation=UUID()
    @ObservationIgnored private var pending:[String:String]=[:]
    @ObservationIgnored private var events:[String]=[]
    var canSend:Bool { running && !draft.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty && draft.utf8.count<=2048 && pending.count<4 }
    var status:String {
        if !EmbeddedMeshCore.available { return String(localized:"Mesh Core is not bundled in this build.") }
        if failed { return String(localized:"Mesh stopped after an error. Diagnostics are available.") }
        if !running { return String(localized:"Mesh is stopped.") }
        if radioCode == "ble_permission_denied" { return String(localized:"Allow Bluetooth access in Settings.") }
        if radioCode == "ble_unavailable" { return String(localized:"Bluetooth is unavailable or turned off.") }
        return String(localized:"Finding nearby Mesh nodes…")
    }
    func start() {
        guard !running,!starting else { return }
        starting=true;failed=false;relay=false;let token=UUID();generation=token
        let previous=cleanup
        reader=Task.detached { [weak self] in
            await previous?.value
            var created:EmbeddedMeshCore?
            do {
                try Task.checkCancellation()
                let directory=try FileManager.default.url(for:.applicationSupportDirectory,in:.userDomainMask,appropriateFor:nil,create:true)
                    .appendingPathComponent("NearbyIM/PublicMesh",isDirectory:true)
                try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:true)
                var excluded=directory;var values=URLResourceValues();values.isExcludedFromBackup=true;try excluded.setResourceValues(values)
                let engine=try EmbeddedMeshCore(directory:directory);created=engine
                let accepted=await self?.adopt(engine,token:token) ?? false
                guard accepted else { engine.close();return }
                while !Task.isCancelled {
                    if let event=try engine.nextEvent() { await self?.apply(event,token:token) }
                }
            } catch {
                await self?.failure(token:token)
            }
            created?.close()
        }
    }
    private func adopt(_ engine:EmbeddedMeshCore,token:UUID)->Bool {
        guard generation==token,starting else { return false };core=engine;return true
    }
    private func apply(_ event:PublicMeshEvent,token:UUID) {
        guard generation==token else { return }
        events.append(event.event);if events.count>64 { events.removeFirst(events.count-64) }
        switch event.event {
        case "ready":
            guard let port=event.loopbackPort else { failure(token:token);return }
            starting=false;running=true
            let adapter=BluetoothMeshRadio(corePort:port) { [weak self] code in
                Task { @MainActor in guard let self,self.generation==token else { return };self.radioCode=code }
            }
            radio=adapter;adapter.start()
        case "neighbor_up": if let peer=event.peer { neighbors.insert(peer) }
        case "neighbor_down": if let peer=event.peer { neighbors.remove(peer) }
        case "relay": relay=event.enabled ?? false
        case "queued":
            if let request=event.request,let text=pending.removeValue(forKey:request),let id=event.id {
                messages.append(Message(id:id,body:text,own:true,hops:0,state:"queued"))
                if draft==text { draft="" }
            }
        case "message":
            if let id=event.id,let text=event.body,!messages.contains(where:{$0.id==id}) {
                messages.append(Message(id:id,body:text,own:false,hops:max(0,(event.path?.count ?? 1)-1),state:"received"))
            }
        case "handed","expired":
            if let index=messages.firstIndex(where:{$0.id==event.id}) { messages[index].state=event.event }
        case "error": pending=[:];failed=true
        default:break
        }
        if messages.count>200 { messages.removeFirst(messages.count-200) }
    }
    private func failure(token:UUID) { guard generation==token else { return };stop();failed=true }
    func stop() {
        generation=UUID();radio?.stop();radio=nil
        let engine=core,task=reader;reader=nil;core=nil;task?.cancel()
        let previous=cleanup
        cleanup=Task.detached { await previous?.value;engine?.close();await task?.value }
        starting=false;running=false;relay=false;neighbors=[];pending=[:]
        for index in messages.indices where messages[index].own && ["queued","handed"].contains(messages[index].state) { messages[index].state="stopped" }
    }
    func send() {
        guard canSend,let core else { return }
        let id=UUID().uuidString;pending[id]=draft
        do { try core.command(["cmd":"send","text":draft,"ttl":600,"hops":4,"request":id]) }
        catch { pending.removeValue(forKey:id);failed=true }
    }
    func setRelay(_ enabled:Bool) {
        do { try core?.command(["cmd":"relay","enabled":enabled]) } catch { failed=true }
    }
    func diagnostics()->String {
        let value:[String:Any]=["schema":"nearby-mesh-apple-v1","system":ProcessInfo.processInfo.operatingSystemVersionString,
            "cpu_cores":ProcessInfo.processInfo.processorCount,"physical_memory_bytes":ProcessInfo.processInfo.physicalMemory,
            "core_bundled":EmbeddedMeshCore.available,"running":running,"starting":starting,"relay":relay,
            "authenticated_neighbors":neighbors.count,"messages":messages.count,"pending_controls":pending.count,
            "radio_status":radioCode,"events":events,"hardware_validation":"pending",
            "notes":"Public plaintext. No bodies, keys, labels, addresses or stable device identifiers. Local stream tests do not prove RF or background availability."]
        return (try? JSONSerialization.data(withJSONObject:value,options:[.prettyPrinted,.sortedKeys])).flatMap { String(data:$0,encoding:.utf8) } ?? "{}"
    }
}
