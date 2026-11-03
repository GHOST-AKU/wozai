package org.nearbymesh.radio;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
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
        System.out.println("BleAdapterHostTest: 7 passed");
    }

    private static void admittedServerConnectionIsHeldExactlyOnce() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Object previous = fixture.link("A"), peer = get(previous,"peer");
            fixture.links().clear(); set(peer,"link",null); set(peer,"attempts",0);
            set(peer,"seen",SystemClock.elapsedRealtime());
            @SuppressWarnings("unchecked") Map<String,Object> peers = (Map<String,Object>)get(fixture.adapter,"peers");
            peers.put("A",peer);
            Method connect = BleAdapter.class.getDeclaredMethod("serverConnection",BluetoothDevice.class,int.class,int.class);
            connect.setAccessible(true);
            connect.invoke(fixture.adapter,new BluetoothDevice("unknown"),0,2);
            check(fixture.server.holds.isEmpty(),"unknown connections must not be held");
            connect.invoke(fixture.adapter,new BluetoothDevice("A"),0,2);
            check(fixture.server.holds.equals(Arrays.asList("A")),"admitted incoming link must explicitly hold server connection");
            connect.invoke(fixture.adapter,new BluetoothDevice("A"),0,2);
            check(fixture.server.holds.size()==1,"hold callback must not recursively acquire connection again");
            Object admitted = fixture.links().get("A");
            invoke(fixture.adapter,"failLink",new Class<?>[]{admitted.getClass(),String.class},admitted,"test_close");
            fixture.server.holdAccepted = false;
            connect.invoke(fixture.adapter,new BluetoothDevice("A"),0,2);
            check(fixture.links().isEmpty() && get(peer,"link")==null,
                    "rejected server hold must release the connection slot");
            check(fixture.events.stream().anyMatch(e -> e.contains("server_hold_rejected")),
                    "hold rejection must have an explicit failure reason");
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

    private static boolean done(Object operation) throws Exception {
        return ((CountDownLatch) get(operation, "done")).getCount() == 0;
    }

    private static final class Fixture implements AutoCloseable {
        final Handler handler = new Handler();
        final BluetoothGattServer server = new BluetoothGattServer();
        final List<String> events = new ArrayList<>();
        final BleAdapter adapter;
        Fixture() throws Exception {
            Map<String, String> arguments = new HashMap<>();
            arguments.put("mode", "ble"); arguments.put("label", "Z"); arguments.put("neighbors", "A,B,C");
            adapter = new BleAdapter(new Context(), handler, RadioConfig.parse(arguments),
                    (event, peer, reason, tx, rx) -> events.add(event + ":" + peer + ":" + reason));
            set(adapter, "server", server);
        }
        Object link(String label) throws Exception {
            Class<?> peerType = Class.forName("org.nearbymesh.radio.BleAdapter$Peer");
            Constructor<?> peerConstructor = peerType.getDeclaredConstructor(String.class);
            peerConstructor.setAccessible(true);
            Object peer = peerConstructor.newInstance(label);
            set(peer, "address", label); set(peer, "device", new BluetoothDevice(label));
            set(peer, "attempts", 4);
            Class<?> linkType = Class.forName("org.nearbymesh.radio.BleAdapter$Link");
            Constructor<?> linkConstructor = linkType.getDeclaredConstructor(peerType, boolean.class);
            linkConstructor.setAccessible(true);
            Object link = linkConstructor.newInstance(peer, false);
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
