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
        System.out.println("BleAdapterHostTest: 2 passed");
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
