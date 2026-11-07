package org.nearbymesh.radio;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Executes the production adapter against narrow host API doubles; no radio evidence. */
public final class BleAdapterHostTest {
    public static void main(String[] args) throws Exception {
        expiringQueuedPeerCannotStartConcurrentIndications();
        closedIndicationRetainsItsAcknowledgementOwner();
        stalePublicCandidatesDoNotBlockNewNeighbor();
        busyNeighborCannotStarveAnother();
        exhaustedPeerRequiresCooldownAndFreshObservation();
        incomingConnectionRecoversAfterCooldownWithoutNewScan();
        admittedServerConnectionIsHeldExactlyOnce();
        indicationBeforeCccdAckIsBufferedButBounded();
        serverCoreWaitsForFirstBytesAndSetupDeadline();
        anchorTimeoutAndStaleCallbacksAreIsolated();
        subscriptionAndAnchorOrdersPreserveBytes();
        missingIndicationCompletionRebuildsServerWithNewEpoch();
        recoveryIsBoundedAndStopCancelsReopen();
        admissionGatesBoundAnchors();
        ackTimeoutAndInterruptedSenderReleaseResources();
        serverIngressFloodAndMtuAreBounded();
        callbackFloodStopsAndReleasesResources();
        targetedCccdAdmissionHandlesMissingMtuAndKeepsGates();
        clientDescriptorAckControlsCoreStart();
        serverAckTimeoutAndPublicationTimeoutRecover();
        System.out.println("BleAdapterHostTest: 20 passed (original 8 retained)");
    }

    private static void indicationBeforeCccdAckIsBufferedButBounded() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object link = fixture.link("A",true);
            set(link,"stage","cccd"); set(link,"transportReady",false); set(link,"mtu",517);
            byte[] hello = new byte[152]; Arrays.fill(hello,(byte)7);
            boolean accepted = (Boolean)invoke(fixture.adapter,"ingress",new Class<?>[]{link.getClass(),byte[].class},link,hello);
            check(accepted && !(Boolean)get(link,"ended"),"valid indication arriving before CCCD callback must not disconnect");
            check(Arrays.equals(((java.io.ByteArrayOutputStream)get(link,"early")).toByteArray(),hello),
                    "early handshake bytes must retain their order and content");
            check(!(Boolean)get(link,"coreStarted"),"buffering must not bypass descriptor acknowledgement or core authentication");
            byte[] fragment = new byte[512];
            while (((java.io.ByteArrayOutputStream)get(link,"early")).size()+512<=8192)
                check((Boolean)invoke(fixture.adapter,"ingress",new Class<?>[]{link.getClass(),byte[].class},link,fragment),"bounded fragments should fit");
            check(!(Boolean)invoke(fixture.adapter,"ingress",new Class<?>[]{link.getClass(),byte[].class},link,fragment),
                    "early indication flood must still fail closed at the byte budget");
        }
        try (Fixture fixture = new Fixture()) {
            Object link = fixture.link("A",true);
            set(link,"stage","services"); set(link,"transportReady",false);
            check(!(Boolean)invoke(fixture.adapter,"ingress",new Class<?>[]{link.getClass(),byte[].class},link,new byte[]{1}),
                    "bytes before subscription setup must remain invalid");
        }
    }

    private static void admittedServerConnectionIsHeldExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object peer = fixture.candidate("A");
            BluetoothDevice discovered = (BluetoothDevice)get(peer,"device");
            fixture.connect("unknown");
            check(discovered.connects == 0 && fixture.server.cancellations.isEmpty(),"unknown global connection owns nothing");
            fixture.connect("A");
            fixture.connect("A");
            Object link = fixture.links().get("A");
            check(discovered.connects == 1 && discovered.transport == BluetoothDevice.TRANSPORT_LE
                    && !discovered.autoConnect, "admitted peripheral must own exactly one explicit LE anchor from discovery");
            check(fixture.server.holds.isEmpty(),"server AUTO transport must never acquire a hold");
            BluetoothGatt anchor = discovered.lastGatt;
            discovered.callback.onConnectionStateChange(anchor,0,2); fixture.handler.drain();
            check(anchor.mtuRequests == 0 && anchor.discoveries == 0 && anchor.descriptorWrites == 0
                    && anchor.characteristicWrites == 0 && anchor.notifications == 0,"anchor must never perform data GATT operations");
            invoke(fixture.adapter,"serverConnection",new Class<?>[]{BluetoothDevice.class,int.class,int.class},new BluetoothDevice("A"),0,0);
            check(!(Boolean)get(link,"ended"),"address-only global disconnect must not destroy live LE ownership");
            invoke(fixture.adapter,"failLink",new Class<?>[]{link.getClass(),String.class},link,"test_close");
            check(anchor.disconnects == 1 && anchor.closes == 1 && fixture.server.cancellations.isEmpty(),"only actual anchor ownership is released once");
            discovered.reject = true;
            fixture.connect("A");
            check(fixture.links().isEmpty() && get(peer,"link") == null,"null anchor releases slot");
        }
    }

    private static void serverCoreWaitsForFirstBytesAndSetupDeadline() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object peer = fixture.candidate("A"); fixture.connect("A");
            Object link = fixture.links().get("A");
            BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            device.callback.onConnectionStateChange(device.lastGatt,0,2); fixture.handler.drain();
            fixture.subscribe(device);
            fixture.handler.advanceMillis(4000);
            check(get(link,"core") == null && !(Boolean)get(link,"transportReady"),"CCCD acceptance must not start the Core auth timer, even after 3 seconds");
            fixture.handler.advanceMillis(11000);
            check(fixture.links().isEmpty() && device.lastGatt.closes == 1,"no first bytes must release slot and anchor at 15 seconds");
        }
    }

    private static void incomingConnectionRecoversAfterCooldownWithoutNewScan() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object link = fixture.link("A"), peer = get(link,"peer");
            @SuppressWarnings("unchecked") Map<String,Object> peers = (Map<String,Object>)get(fixture.adapter,"peers");
            peers.put("A",peer);
            set(peer,"seen",SystemClock.elapsedRealtime());
            invoke(fixture.adapter,"failLink",new Class<?>[]{link.getClass(),String.class},link,"test_disconnect");
            long deadline = (Long)get(peer,"retryAt");
            Method connect = BleAdapter.class.getDeclaredMethod("serverConnection",BluetoothDevice.class,int.class,int.class);
            connect.setAccessible(true);
            connect.invoke(fixture.adapter,new BluetoothDevice("A"),0,2);
            check(fixture.links().isEmpty(),"incoming connection cannot bypass cooldown");
            SystemClock.advanceMillis(deadline-SystemClock.elapsedRealtime()+1);
            connect.invoke(fixture.adapter,new BluetoothDevice("A"),0,2);
            check(fixture.links().containsKey("A"),"known incoming connection after cooldown must not require another scan callback");
            check((Integer)get(peer,"attempts")==1,"recovery opens exactly one bounded attempt window");
        }
    }

    private static void exhaustedPeerRequiresCooldownAndFreshObservation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object link = fixture.link("A"), peer = get(link, "peer");
            @SuppressWarnings("unchecked") Map<String,Object> peers = (Map<String,Object>) get(fixture.adapter,"peers");
            peers.put("A",peer);
            set(peer,"seen",SystemClock.elapsedRealtime());
            invoke(fixture.adapter,"failLink",new Class<?>[]{link.getClass(),String.class},link,"test_disconnect");
            long cooldown = (Long)get(peer,"retryAt") - SystemClock.elapsedRealtime();
            check(cooldown >= 60000, "exhaustion must create a bounded cooldown");
            // Silence must not erase the cooldown when ordinary candidate expiry elapses.
            SystemClock.advanceMillis(40000);
            observe(fixture.adapter,"A");
            check(peers.get("A")==peer && (Integer)get(peer,"attempts")==4,
                    "rediscovery must not bypass cooldown through candidate expiry");
            SystemClock.advanceMillis(cooldown-40000-1);
            observe(fixture.adapter,"A");
            check((Integer)get(peer,"attempts")==4,"advertisements before deadline cannot reset attempts");
            SystemClock.advanceMillis(1);
            check((Integer)get(peer,"attempts")==4,"time alone must not start another attempt window");
            observe(fixture.adapter,"A");
            check((Integer)get(peer,"attempts")==0,"fresh observation after cooldown must recover exhausted peer");
        }
    }

    private static void busyNeighborCannotStarveAnother() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object a = fixture.link("A"), b = fixture.link("B");
            fixture.queue(a);
            fixture.pump();
            Object waiting = fixture.queue(b);
            for (int i = 0; i < 8; i++) fixture.queue(a);
            fixture.ack("A");
            check(fixture.server.indications.equals(Arrays.asList("A", "B")),
                    "busy A must yield the next acknowledged slot to waiting B: " + fixture.server.indications);
            check(!done(waiting), "scheduling B must not acknowledge it");
            fixture.ack("B");
            check(done(waiting), "B completes only on its own acknowledgement");
            check(fixture.server.indications.equals(Arrays.asList("A", "B", "A")),
                    "round robin must resume A without losing queued work");
        }
    }

    private static void stalePublicCandidatesDoNotBlockNewNeighbor() throws Exception {
        Handler handler=new Handler();
        BleAdapter adapter=new BleAdapter(new Context(),handler,RadioConfig.publicMesh("ble","Z",39770),
            (event,peer,reason,tx,rx)->{});
        try {
            for(String label:new String[]{"A","B","C","D"})observe(adapter,label);
            @SuppressWarnings("unchecked") Map<String,Object> peers=(Map<String,Object>)get(adapter,"peers");
            check(peers.size()==4,"public candidate bound must stay four");
            SystemClock.advanceMillis(30001);
            observe(adapter,"E");
            check(peers.size()==1&&peers.containsKey("E"),"vanished public candidates must expire before admitting a new label");
        }finally{adapter.close();handler.drain();}
    }
    private static void observe(BleAdapter adapter,String label)throws Exception {
        android.bluetooth.le.ScanResult result=new android.bluetooth.le.ScanResult(new BluetoothDevice(label),
            new android.bluetooth.le.ScanRecord(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        invoke(adapter,"observed",new Class<?>[]{android.bluetooth.le.ScanResult.class},result);
    }

    private static void expiringQueuedPeerCannotStartConcurrentIndications() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object expired = fixture.link("A"), second = fixture.link("B"), third = fixture.link("C");
            Object expiredOperation = fixture.queue(expired);
            SystemClock.advanceMillis(5000);
            Object secondOperation = fixture.queue(second), thirdOperation = fixture.queue(third);
            fixture.pump();
            check(fixture.server.indications.equals(Arrays.asList("B")),
                    "expiring A must submit only B until its acknowledgement; submissions=" + fixture.server.indications);
            check(done(expiredOperation), "expired operation must fail promptly");
            check(!done(secondOperation) && !done(thirdOperation), "submission is not a delivery acknowledgement");
            fixture.ack("B");
            check(done(secondOperation) && !done(thirdOperation), "B acknowledgement must complete only B");
            check(fixture.server.indications.equals(Arrays.asList("B", "C")), "C must start after B acknowledgement");
            fixture.ack("C");
            check(done(thirdOperation), "C must retain its own acknowledgement");
        }
    }

    private static void closedIndicationRetainsItsAcknowledgementOwner() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object first = fixture.link("A"), second = fixture.link("B");
            Object firstOperation = fixture.queue(first), secondOperation = fixture.queue(second);
            fixture.pump();
            invoke(fixture.adapter, "failLink", new Class<?>[] {first.getClass(), String.class}, first, "test_disconnect");
            check(done(firstOperation) && !done(secondOperation), "disconnect must fail only the old operation");
            check(fixture.server.indications.equals(Arrays.asList("A")), "closed in-flight indication must block another submission");
            fixture.ack("B");
            check(!done(secondOperation) && fixture.server.indications.size() == 1,
                    "an unrelated callback must not clear the closed indication");
            fixture.ack("A");
            check(fixture.server.indications.equals(Arrays.asList("A", "B")), "old callback must release the next peer");
            check(!done(secondOperation), "old callback must not acknowledge the next peer");
            fixture.ack("B");
            check(done(secondOperation), "new peer must require its own callback");
        }
    }

    private static void anchorTimeoutAndStaleCallbacksAreIsolated() throws Exception {
        try (Fixture f = new Fixture()) {
            Object peer = f.candidate("A");
            BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            device.connectDelayMillis = 2000; f.connect("A"); device.connectDelayMillis = 0;
            Object old = f.links().get("A"); BluetoothGatt oldGatt = device.lastGatt;
            BluetoothGattCallback oldCallback = device.callback;
            f.subscribe(device); f.rx(device,new byte[]{1});
            check(get(old,"core") != null && f.coreTasks.tasks.size() == 1,"first RX may progress Core while anchor callback is pending");
            f.handler.advanceMillis(12999);
            check(!(Boolean)get(old,"ended"),"anchor deadline must not fire early");
            f.handler.advanceMillis(1);
            check(f.links().isEmpty() && oldGatt.closes == 1 && oldGatt.disconnects == 1,"anchor deadline remains15s from admission despite2s registration delay and progressed Core");
            f.connect("A"); Object fresh = f.links().get("A"); BluetoothGatt freshGatt = device.lastGatt;
            oldCallback.onConnectionStateChange(oldGatt,0,2);
            oldCallback.onConnectionStateChange(oldGatt,133,0);
            device.callback.onConnectionStateChange(oldGatt,133,0);
            f.handler.drain();
            check(!(Boolean)get(fresh,"ended") && !(Boolean)get(fresh,"anchorConnected"),"old Link and wrong GATT identity cannot mutate current anchor");
            device.callback.onConnectionStateChange(freshGatt,133,0); f.handler.drain();
            check(f.links().isEmpty() && freshGatt.closes == 1,"anchor failure cleans current ownership");
            f.connect("A"); BluetoothGatt stoppedGatt = device.lastGatt; BluetoothGattCallback stoppedCallback = device.callback;
            f.adapter.close(); f.handler.drain();
            stoppedCallback.onConnectionStateChange(stoppedGatt,0,2); oldCallback.onConnectionStateChange(oldGatt,0,0);
            f.handler.advanceMillis(20000);
            check(f.links().isEmpty() && stoppedGatt.closes == 1 && oldGatt.closes == 1,"late callbacks after stop cannot recreate or double-close anchors");
        }
    }

    private static void subscriptionAndAnchorOrdersPreserveBytes() throws Exception {
        for (boolean anchorFirst : new boolean[]{true,false}) {
            try (Fixture f = new Fixture(); java.net.ServerSocket listener = new java.net.ServerSocket(0)) {
                // Use a real loopback CoreBridge to verify the buffered byte order, not radio behavior.
                set(f.adapter,"config",RadioConfig.publicMesh("ble","Z",listener.getLocalPort()));
                Object peer = f.candidate("A"); f.connect("A");
                BluetoothDevice device = (BluetoothDevice)get(peer,"device"); Object link = f.links().get("A");
                if (anchorFirst) { device.callback.onConnectionStateChange(device.lastGatt,0,2); f.handler.drain(); }
                f.subscribe(device); f.handler.advanceMillis(4000);
                check(get(link,"core") == null && f.coreTasks.tasks.isEmpty(),"delayed descriptor acceptance alone starts no Core");
                f.rx(device,new byte[]{1,2,3}); f.rx(device,new byte[]{4,5}); f.subscribe(device);
                if (!anchorFirst) { device.callback.onConnectionStateChange(device.lastGatt,0,2); f.handler.drain(); }
                check(f.coreTasks.tasks.size() == 1 && Arrays.equals(((java.io.ByteArrayOutputStream)get(link,"early")).toByteArray(),new byte[]{1,2,3,4,5}),
                        "both callback orders buffer all bytes exactly once and start Core once");
                f.coreTasks.tasks.remove(0).run(); f.handler.drain();
                listener.setSoTimeout(2000);
                try (java.net.Socket core = listener.accept()) {
                    core.setSoTimeout(2000); byte[] received = new byte[5]; new java.io.DataInputStream(core.getInputStream()).readFully(received);
                    check(Arrays.equals(received,new byte[]{1,2,3,4,5}) && (Boolean)get(link,"coreStarted"),"production CoreBridge flush must preserve exact early byte order");
                    f.handler.advanceMillis(11000);
                    check(!(Boolean)get(link,"ended") && device.lastGatt.closes == 0,"completed setup survives both deadlines");
                }
            }
        }
    }

    private static void missingIndicationCompletionRebuildsServerWithNewEpoch() throws Exception {
        try (Fixture f = new Fixture()) {
            f.startServer(); BluetoothGattServer oldServer = f.currentServer(); BluetoothGattServerCallback oldCallback = f.callback();
            Object peer = f.candidate("A"); f.connect("A"); Object old = f.links().get("A");
            BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            device.callback.onConnectionStateChange(device.lastGatt,0,2); f.handler.drain();
            set(old,"subscribed",true); set(old,"transportReady",true);
            Object other = f.link("B"), client = f.link("C",true);
            BluetoothGatt clientGatt = new BluetoothGatt(); set(client,"gatt",clientGatt);
            Object pending = f.queue(old); f.pump();
            device.callback.onConnectionStateChange(device.lastGatt,0,0); f.handler.drain();
            check(done(pending) && oldServer.closes == 0,"accepted indication disconnect retains old session until deadline");
            f.connect("A"); check(!f.links().containsKey("A"),"same address cannot reuse an ambiguous ACK owner");
            f.handler.advanceMillis(5000);
            check(oldServer.closes == 1 && (Boolean)get(other,"ended") && !(Boolean)get(client,"ended"),"lost completion retires registration and peripheral links, retaining client data link");
            check(f.scanner.starts == 1 && f.scanner.stops == 0 && f.advertiser.stops == 1,"recovery stops advertising without restarting scanner");
            check(f.events.stream().anyMatch(e -> e.equals("ble_link_down:B:gatt_server_recovery")),"healthy peripheral invalidated by server recovery emits real link-down");
            f.handler.advanceMillis(999); check(f.opened.size() == 1,"reopen waits its backoff");
            f.handler.advanceMillis(1); check(f.opened.size() == 2,"new server registration opens after backoff");
            f.publish(); check(f.scanner.starts == 1 && f.advertiser.starts == 2,"service publication restarts advertising only");
            f.advertiser.callback.onStartSuccess(new android.bluetooth.le.AdvertiseSettings.Builder().build()); f.handler.drain();
            check(f.events.stream().noneMatch(e -> e.startsWith("ble_error:"))
                    && f.events.stream().anyMatch(e -> e.equals("ble_server_recovering::indication_cancellation_timeout"))
                    && f.events.stream().anyMatch(e -> e.equals("ble_server_recovered::scan_and_advertise")),
                    "successful recovery emits progress without a terminal Android session error");
            f.connect("A"); Object fresh = f.links().get("A");
            set(fresh,"subscribed",true); set(fresh,"transportReady",true);
            Object next = f.queue(fresh); f.pump(); BluetoothGattServer freshServer = f.currentServer();
            // Every old server callback, including malformed values, must be inert at dispatch.
            oldCallback.onNotificationSent(device,0);
            oldCallback.onConnectionStateChange(device,133,0);
            oldCallback.onMtuChanged(device,1);
            oldCallback.onDescriptorWriteRequest(device,1,f.cccd(),false,true,0,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            oldCallback.onDescriptorReadRequest(device,1,0,f.cccd());
            oldCallback.onCharacteristicWriteRequest(device,1,f.rxCharacteristic(),false,true,0,new byte[]{9});
            oldCallback.onCharacteristicReadRequest(device,1,0,f.rxCharacteristic());
            oldCallback.onExecuteWrite(device,1,true);
            oldCallback.onServiceAdded(133,oldServer.service);
            f.handler.drain();
            check(!done(next) && !(Boolean)get(fresh,"ended") && freshServer.responses == 0 && (Integer)get(fresh,"mtu") == 23,
                    "old epoch must neither ACK, close, respond to nor mutate new same-address link");
            f.ack("A"); check(done(next) && (Long)get(fresh,"txBytes") == 1L,"fresh callback exclusively credits fresh operation");
            check(clientGatt.closes == 0 && !((java.util.concurrent.atomic.AtomicBoolean)get(f.adapter,"closed")).get(),"successful server recovery leaves adapter and data client alive");
        }
    }

    private static void recoveryIsBoundedAndStopCancelsReopen() throws Exception {
        try (Fixture f = new Fixture()) {
            f.startServer(); f.rejectServers = true;
            invoke(f.adapter,"recoverServer",new Class<?>[]{String.class},"test_missing_ack");
            f.handler.advanceMillis(999); check(f.opened.size() == 1,"first recovery waits one second");
            f.handler.advanceMillis(1); check(f.opened.size() == 2,"first bounded reopen");
            f.handler.advanceMillis(1999); check(f.opened.size() == 2,"second recovery waits two seconds");
            f.handler.advanceMillis(1); check(f.opened.size() == 3,"second bounded reopen");
            f.handler.advanceMillis(4000);
            check(f.opened.size() == 4 && ((java.util.concurrent.atomic.AtomicBoolean)get(f.adapter,"closed")).get(),"three failed recoveries terminate visibly rather than spin");
            check(f.events.stream().anyMatch(e -> e.endsWith(":gatt_server_recovery_exhausted")),"exhaustion must be visible");
            f.handler.advanceMillis(60000); check(f.opened.size() == 4,"exhausted adapter must not reopen again");
        }
        try (Fixture f = new Fixture()) {
            f.startServer(); invoke(f.adapter,"recoverServer",new Class<?>[]{String.class},"test_stop");
            f.adapter.close(); f.handler.advanceMillis(30000);
            check(f.opened.size() == 1 && get(f.adapter,"indicating") == null && get(f.adapter,"lastIndicatedAddress") == null,
                    "stop invalidates recovery timer and resets indication ownership");
        }
    }

    private static void admissionGatesBoundAnchors() throws Exception {
        try (Fixture f = new Fixture()) {
            Object wrong = f.candidate("ZZ"); f.connect("ZZ");
            check(((BluetoothDevice)get(wrong,"device")).connects == 0,"role order forbids wrong peripheral anchor");
            Object cooling = f.candidate("A"); set(cooling,"attempts",4); set(cooling,"retryAt",SystemClock.elapsedRealtime()+60000);
            f.connect("A"); check(((BluetoothDevice)get(cooling,"device")).connects == 0,"cooldown forbids anchor acquisition");
            for (String label : new String[]{"B","C","D","E"}) { f.candidate(label); f.connect(label); }
            check(f.links().size() == 4,"maximum four admitted peripheral links/anchors");
            Object fifth = f.candidate("F"); f.connect("F");
            check(f.links().size() == 4 && ((BluetoothDevice)get(fifth,"device")).connects == 0,"fifth anchor rejected without acquisition");
        }
    }

    private static void ackTimeoutAndInterruptedSenderReleaseResources() throws Exception {
        try (Fixture f = new Fixture()) {
            Object link = f.link("A",true); BluetoothGatt gatt = new BluetoothGatt(); set(link,"gatt",gatt);
            Object op = f.queue(link); invoke(f.adapter,"pumpClient",new Class<?>[]{link.getClass()},link);
            f.handler.advanceMillis(4999); check(!done(op),"ACK timer waits deadline"); f.handler.advanceMillis(1);
            check(done(op) && get(op,"error") != null && gatt.closes == 1 && f.links().isEmpty(),"missing client ACK fails operation and releases GATT");
        }
        try (Fixture f = new Fixture()) {
            Object link = f.link("A",true); BluetoothGatt gatt = new BluetoothGatt(); set(link,"gatt",gatt);
            java.util.concurrent.atomic.AtomicReference<Throwable> result = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(f.adapter,"sendToRadio",new Class<?>[]{link.getClass(),byte[].class},link,new byte[]{1}); }
                catch (Throwable failure) { result.set(failure); }
            });
            worker.start();
            long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (get(link,"pending") == null && System.nanoTime() < deadline) { f.handler.drain(); Thread.yield(); }
            check(get(link,"pending") != null,"worker must have submitted its operation before interruption");
            Object pending = get(link,"pending"); worker.interrupt(); worker.join(2000); f.handler.drain();
            check(!worker.isAlive() && result.get() != null && done(pending) && gatt.closes == 1
                    && ((java.util.concurrent.Semaphore)get(link,"sender")).availablePermits() == 1,"interrupted sender releases operation, link and sender permit");
        }
    }

    private static void serverIngressFloodAndMtuAreBounded() throws Exception {
        try (Fixture f = new Fixture()) {
            Object peer = f.candidate("A"); f.connect("A"); BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            Object link = f.links().get("A"); f.subscribe(device); set(link,"mtu",517);
            for (int i=0;i<16;i++) f.rx(device,new byte[512]);
            check(((java.io.ByteArrayOutputStream)get(link,"early")).size() == 8192 && f.coreTasks.tasks.size() == 1,"peripheral early buffer has exact 8192-byte bound");
            f.rx(device,new byte[]{1}); check(f.links().isEmpty() && device.lastGatt.closes == 1,"early RX flood releases anchor");
        }
        try (Fixture f = new Fixture()) {
            Object peer = f.candidate("A"); f.connect("A"); BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            f.subscribe(device); f.rx(device,new byte[21]);
            check(f.links().isEmpty() && f.coreTasks.tasks.isEmpty() && device.lastGatt.closes == 1,"oversize ATT bytes cannot start Core");
        }
    }

    private static void callbackFloodStopsAndReleasesResources() throws Exception {
        try (Fixture f = new Fixture()) {
            Object peer = f.candidate("A"); f.connect("A"); BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            for (int i=0;i<129;i++) f.callback().onMtuChanged(device,23);
            f.handler.drain();
            check(f.links().isEmpty() && device.lastGatt.closes == 1 && f.server.closes == 1,"callback flood remains fail-closed and releases owned resources");
        }
    }

    private static void targetedCccdAdmissionHandlesMissingMtuAndKeepsGates() throws Exception {
        try (Fixture f = new Fixture()) {
            Object peer = f.candidate("A"); BluetoothDevice device = (BluetoothDevice)get(peer,"device");
            f.callback().onMtuChanged(device,517); f.handler.drain();
            f.subscribe(device); Object link = f.links().get("A");
            check(link != null && device.connects == 1 && (Boolean)get(link,"receiveMtuUnknown"),"targeted subscription admits known peer even without global CONNECTED");
            f.rx(device,new byte[152]);
            check(get(link,"core") != null && (Integer)get(link,"mtu") == 23,"MTU-before-CCCD accepts Hello without inventing negotiated transmit MTU");
            // Exercise production chunking from a worker and ACK each emitted fragment.
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { invoke(f.adapter,"sendToRadio",new Class<?>[]{link.getClass(),byte[].class},link,new byte[45]); }
                catch (Throwable error) { failure.set(error); }
            });
            worker.start(); int acked = 0; long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while (worker.isAlive() && System.nanoTime() < deadline) {
                f.handler.drain();
                if (f.server.indications.size() > acked) { f.ack("A"); acked++; }
                Thread.yield();
            }
            worker.join(100);
            check(!worker.isAlive() && failure.get() == null && f.server.indicatedValues.size() == 3
                    && f.server.indicatedValues.get(0).length == 20 && f.server.indicatedValues.get(1).length == 20
                    && f.server.indicatedValues.get(2).length == 5,"unknown receive MTU leaves production outbound fragments at 20 bytes");
            f.callback().onMtuChanged(device,100); f.handler.drain();
            check(!(Boolean)get(link,"receiveMtuUnknown") && (Integer)get(link,"mtu") == 100,"actual Link MTU ends fallback");
            f.rx(device,new byte[98]); check(f.links().isEmpty() && device.lastGatt.closes == 1,"observed MTU strictly limits subsequent RX");
            f.subscribe(device); f.rx(device,new byte[513]);
            check(f.links().isEmpty() && device.lastGatt.closes == 1,"unknown MTU still rejects fragments above absolute512 limit");
        }
        try (Fixture f = new Fixture()) {
            f.subscribe(new BluetoothDevice("unknown")); check(f.links().isEmpty(),"targeted CCCD cannot admit undiscovered device");
            Object wrong = f.candidate("ZZ"); f.subscribe((BluetoothDevice)get(wrong,"device"));
            check(((BluetoothDevice)get(wrong,"device")).connects == 0,"targeted CCCD preserves role gate");
            Object cooling = f.candidate("A"); set(cooling,"attempts",4); set(cooling,"retryAt",SystemClock.elapsedRealtime()+60000);
            f.subscribe((BluetoothDevice)get(cooling,"device")); check(((BluetoothDevice)get(cooling,"device")).connects == 0,"targeted CCCD preserves cooldown gate");
            Object invalid = f.candidate("B"); BluetoothDevice device = (BluetoothDevice)get(invalid,"device");
            f.callback().onDescriptorWriteRequest(device,1,f.cccd(),true,true,0,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            f.callback().onDescriptorWriteRequest(device,1,f.cccd(),false,true,0,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
            f.handler.drain(); check(device.connects == 0,"prepared or disabled CCCD cannot trigger admission");
            for (String label : new String[]{"C","D","E","F"}) { f.candidate(label); f.connect(label); }
            f.subscribe(device); check(device.connects == 0 && f.links().size() == 4,"targeted CCCD preserves slot cap");
        }
    }

    private static void clientDescriptorAckControlsCoreStart() throws Exception {
        try (Fixture f = new Fixture()) {
            Object link = f.link("A",true); BluetoothGatt gatt = new BluetoothGatt();
            set(link,"gatt",gatt); set(link,"transportReady",false); set(link,"stage","cccd"); set(link,"mtu",517);
            BluetoothGattCallback callback = (BluetoothGattCallback)invoke(f.adapter,"clientCallback",new Class<?>[]{link.getClass()},link);
            check((Boolean)invoke(f.adapter,"ingress",new Class<?>[]{link.getClass(),byte[].class},link,new byte[152]),"legacy peripheral early Hello buffered");
            f.handler.advanceMillis(4000); check(f.coreTasks.tasks.isEmpty(),"central must wait beyond3s for descriptor callback without starting Core");
            callback.onDescriptorWrite(gatt,f.cccd(),0); f.handler.drain();
            callback.onDescriptorWrite(gatt,f.cccd(),0); f.handler.drain();
            check(f.coreTasks.tasks.size() == 1 && get(link,"core") != null,"successful descriptor ACK starts central Core once");
        }
    }

    private static void serverAckTimeoutAndPublicationTimeoutRecover() throws Exception {
        try (Fixture f = new Fixture()) {
            f.startServer(); BluetoothGattServer initial = f.currentServer();
            Object link = f.link("A"); Object op = f.queue(link); f.pump();
            f.handler.advanceMillis(5000);
            check(done(op) && get(op,"error") != null && f.links().isEmpty() && initial.closes == 0,"server ACK timeout releases caller but retains ambiguous indication ownership");
            f.handler.advanceMillis(5000);
            check(initial.closes == 1 && get(f.adapter,"indicating") == null,"uncompleted timed-out indication retires whole registration");
            f.handler.advanceMillis(1000); BluetoothGattServer reopened = f.currentServer();
            // The initial registration's 15s publication timer must not retire its successor.
            f.handler.advanceMillis(4000); check(reopened.closes == 0,"old registration timeout cannot expire new publication");
            f.handler.advanceMillis(10999); check(reopened.closes == 0,"new publication retains its full15s budget");
            f.handler.advanceMillis(1); check(reopened.closes == 1,"unpublished new server eventually times out and releases registration");
            f.handler.advanceMillis(2000); BluetoothGattServer failed = f.currentServer();
            f.callback().onServiceAdded(133,failed.service); f.handler.drain();
            check(failed.closes == 1 && !((java.util.concurrent.atomic.AtomicBoolean)get(f.adapter,"closed")).get(),"service callback failure enters remaining bounded recovery");
        }
    }

    private static boolean done(Object operation) throws Exception {
        return ((CountDownLatch) get(operation, "done")).getCount() == 0;
    }

    private static final class Fixture implements AutoCloseable {
        final Handler handler = new Handler();
        final BluetoothGattServer server = new BluetoothGattServer();
        final List<String> events = new ArrayList<>();
        final List<BluetoothGattServer> opened = new ArrayList<>();
        final android.bluetooth.le.BluetoothLeScanner scanner = new android.bluetooth.le.BluetoothLeScanner();
        final android.bluetooth.le.BluetoothLeAdvertiser advertiser = new android.bluetooth.le.BluetoothLeAdvertiser();
        final ControlledCoreStarts coreTasks = new ControlledCoreStarts();
        boolean rejectServers;
        final BleAdapter adapter;
        Fixture() throws Exception {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("mode", "ble"); arguments.put("label", "Z"); arguments.put("neighbors", "A,B,C");
            adapter = new BleAdapter(new Context(), handler, RadioConfig.parse(arguments),
                    (event, peer, reason, tx, rx) -> events.add(event + ":" + peer + ":" + reason), callback -> {
                        BluetoothGattServer next = new BluetoothGattServer(); opened.add(next); return rejectServers ? null : next;
                    });
            set(adapter, "server", server); set(adapter,"serviceAdded",true);
            set(adapter,"scanner",scanner); set(adapter,"advertiser",advertiser);
            ((java.util.concurrent.ThreadPoolExecutor)get(adapter,"coreStarts")).shutdownNow();
            set(adapter,"coreStarts",coreTasks);
        }
        Object candidate(String label) throws Exception {
            Object old = link(label), peer = get(old,"peer");
            links().remove(label); set(peer,"link",null); set(peer,"attempts",0); set(peer,"seen",SystemClock.elapsedRealtime());
            @SuppressWarnings("unchecked") Map<String,Object> peers = (Map<String,Object>)get(adapter,"peers");
            peers.put(label,peer); return peer;
        }
        void connect(String label) throws Exception {
            invoke(adapter,"serverConnection",new Class<?>[]{BluetoothDevice.class,int.class,int.class},new BluetoothDevice(label),0,2);
        }
        BluetoothGattServerCallback callback() throws Exception { return (BluetoothGattServerCallback)get(adapter,"serverCallback"); }
        BluetoothGattServer currentServer() throws Exception { return (BluetoothGattServer)get(adapter,"server"); }
        void startServer() throws Exception {
            set(adapter,"serviceAdded",false); invoke(adapter,"begin",new Class<?>[0]); publish();
        }
        void publish() throws Exception { callback().onServiceAdded(0,currentServer().service); handler.drain(); }
        BluetoothGattCharacteristic rxCharacteristic() {
            return new BluetoothGattCharacteristic(java.util.UUID.fromString("eed3c890-5c83-4f9c-a88d-94bc822c71e1"),8,16);
        }
        BluetoothGattDescriptor cccd() {
            BluetoothGattCharacteristic tx = new BluetoothGattCharacteristic(java.util.UUID.fromString("eed3c890-5c83-4f9c-a88d-94bc822c71e2"),32,0);
            BluetoothGattDescriptor cccd = new BluetoothGattDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),17);
            tx.addDescriptor(cccd); return cccd;
        }
        void subscribe(BluetoothDevice device) throws Exception {
            callback().onDescriptorWriteRequest(device,1,cccd(),false,true,0,BluetoothGattDescriptor.ENABLE_INDICATION_VALUE); handler.drain();
        }
        void rx(BluetoothDevice device, byte[] value) throws Exception {
            callback().onCharacteristicWriteRequest(device,1,rxCharacteristic(),false,true,0,value); handler.drain();
        }
        Object link(String label) throws Exception { return link(label,false); }
        Object link(String label, boolean client) throws Exception {
            Class<?> peerType = Class.forName("org.nearbymesh.radio.BleAdapter$Peer");
            Constructor<?> peerConstructor = peerType.getDeclaredConstructor(String.class);
            peerConstructor.setAccessible(true);
            Object peer = peerConstructor.newInstance(label);
            set(peer, "address", label); set(peer, "device", new BluetoothDevice(label));
            set(peer, "attempts", 4);
            Class<?> linkType = Class.forName("org.nearbymesh.radio.BleAdapter$Link");
            Constructor<?> linkConstructor = linkType.getDeclaredConstructor(peerType, boolean.class);
            linkConstructor.setAccessible(true);
            Object link = linkConstructor.newInstance(peer, client);
            set(peer, "link", link); set(link, "subscribed", true); set(link, "transportReady", true);
            links().put(label, link);
            return link;
        }
        Object queue(Object link) throws Exception {
            Class<?> type = Class.forName("org.nearbymesh.radio.BleAdapter$Operation");
            Constructor<?> constructor = type.getDeclaredConstructor(byte[].class);
            constructor.setAccessible(true);
            Object operation = constructor.newInstance((Object) new byte[] {1});
            @SuppressWarnings("unchecked") ArrayDeque<Object> queue = (ArrayDeque<Object>) get(link, "queue");
            queue.addLast(operation); set(link, "queuedBytes", 1);
            return operation;
        }
        @SuppressWarnings("unchecked") Map<String, Object> links() throws Exception {
            return (Map<String, Object>) get(adapter, "links");
        }
        void pump() throws Exception { invoke(adapter, "pumpServer", new Class<?>[0]); }
        void ack(String address) throws Exception {
            ((BluetoothGattServerCallback) get(adapter, "serverCallback")).onNotificationSent(
                    new BluetoothDevice(address), BluetoothGatt.GATT_SUCCESS);
            handler.drain();
        }
        @Override public void close() { adapter.close(); handler.drain(); }
    }

    /** Controls only when production CoreBridge.start runs; does not replace its byte path. */
    private static final class ControlledCoreStarts extends java.util.concurrent.ThreadPoolExecutor {
        final List<Runnable> tasks = new ArrayList<>();
        ControlledCoreStarts() { super(0,4,30,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.SynchronousQueue<>()); }
        @Override public void execute(Runnable task) { tasks.add(task); }
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
