package org.nearbymesh.radio;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BLE-only lab byte transport. Labels restrict discovery and connection roles; the Rust
 * core alone authenticates identities, frames the stream, and routes signed messages.
 * All mutable radio state and Android API operations belong to the supplied Handler.
 */
public final class BleAdapter implements RadioAdapter {
    // New lab namespace, deliberately unrelated to Wozai production and Bitchat UUIDs.
    static final UUID SERVICE = UUID.fromString("eed3c890-5c83-4f9c-a88d-94bc822c71e0");
    private static final UUID RX = UUID.fromString("eed3c890-5c83-4f9c-a88d-94bc822c71e1");
    private static final UUID TX = UUID.fromString("eed3c890-5c83-4f9c-a88d-94bc822c71e2");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final ParcelUuid SERVICE_ID = new ParcelUuid(SERVICE);
    private static final int MAX_LINKS = 4, MAX_ATTEMPTS = 4, MAX_QUEUED_OPS = 8;
    private static final int MAX_SCAN_BATCH = 32;
    private static final int MAX_QUEUED_BYTES = 4096, MAX_EARLY_BYTES = 8192;
    private static final long SETUP_MS = 15000, OP_MS = 5000, SEEN_MS = 30000;

    private final Context context;
    private final Handler handler;
    private final RadioConfig config;
    private final RadioEvents events;
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    private final Object callbackGate = new Object();
    private final CallbackBudget callbackBudget = new CallbackBudget(128);
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, Link> links = new LinkedHashMap<>();
    private final ThreadPoolExecutor coreStarts;
    private BluetoothManager manager;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer server;
    private BluetoothGattCharacteristic serverTx;
    private boolean scanning, advertising, serviceAdded;
    // Android requires onNotificationSent before sending any next server indication.
    private Link indicating;
    private String lastIndicatedAddress;

    public BleAdapter(Context context, Handler handler, RadioConfig config, RadioEvents events) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.config = config;
        this.events = events;
        coreStarts = new ThreadPoolExecutor(0, MAX_LINKS, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "mesh-ble-core-start");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    @Override public void start() throws Exception {
        if (!"ble".equals(config.mode)) throw new IllegalArgumentException("ble_only_required");
        if (!started.compareAndSet(false, true) || closed.get())
            throw new IllegalStateException("ble_closed_or_started");
        try {
            if (Build.VERSION.SDK_INT < 26) throw new IOException("ble_requires_api26");
            requirePermissions();
            manager = context.getSystemService(BluetoothManager.class);
            adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
                throw new IOException("ble_unavailable");
            if (!adapter.isEnabled()) throw new IOException("bluetooth_disabled");
            scanner = adapter.getBluetoothLeScanner();
            advertiser = adapter.getBluetoothLeAdvertiser();
            if (scanner == null || advertiser == null || !adapter.isMultipleAdvertisementSupported())
                throw new IOException("ble_scan_and_advertise_required");
            if (!handler.post(() -> guarded(this::begin))) throw new IOException("ble_handler_stopped");
        } catch (SecurityException denied) {
            handler.post(() -> emit("ble_error", "", "permission_denied", 0, 0));
            close();
            throw new SecurityException("BLE requires scan, advertise and connect permissions");
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void requirePermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= 31
                ? new String[] {Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT}
                : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
        for (String permission : permissions)
            if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("ble_permission_denied");
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void begin() {
        server = manager.openGattServer(context, serverCallback);
        if (server == null) { failAdapter("gatt_server_unavailable"); return; }
        BluetoothGattService service = new BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE));
        serverTx = new BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0);
        serverTx.addDescriptor(new BluetoothGattDescriptor(CCCD,
                BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        service.addCharacteristic(serverTx);
        if (!server.addService(service)) { failAdapter("gatt_add_service_rejected"); return; }
        handler.postDelayed(() -> guarded(() -> {
            if (!serviceAdded) failAdapter("gatt_add_service_timeout");
        }), SETUP_MS);
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void startDiscovery() {
        serviceAdded = true;
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(SERVICE_ID).build();
        scanner.startScan(Collections.singletonList(filter), new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), scanCallback);
        scanning = true;
        AdvertiseData primary = new AdvertiseData.Builder().addServiceUuid(SERVICE_ID).build();
        // Legacy advertising and scan response each have a31-byte budget. UUID+label
        // belongs in the response; never include a system device name or MAC in events.
        AdvertiseData response = new AdvertiseData.Builder().addServiceData(SERVICE_ID,
                config.label.getBytes(StandardCharsets.US_ASCII)).build();
        advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true)
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(0).build(),
                primary, response, advertiseCallback);
        advertising = true;
    }

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override public void onStartSuccess(AdvertiseSettings settings) {
            dispatch(() -> emit("ble_ready", "", "scan_and_advertise", 0, 0));
        }
        @Override public void onStartFailure(int error) {
            dispatch(() -> failAdapter("advertise_failed_" + error));
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { dispatch(() -> observed(result)); }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (closed.get()) return;
            if (results.size() > MAX_SCAN_BATCH) { callbackOverflow(); return; }
            List<ScanResult> copy = new ArrayList<>(results);
            dispatch(() -> { for (ScanResult result : copy) observed(result); });
        }
        @Override public void onScanFailed(int error) { dispatch(() -> failAdapter("scan_failed_" + error)); }
    };

    private void observed(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        byte[] data = record == null ? null : record.getServiceData(SERVICE_ID);
        if (data == null || data.length < 1 || data.length > 8) return;
        String label = new String(data, StandardCharsets.US_ASCII);
        if (!label.matches("[A-Za-z0-9_-]{1,8}") || !config.accepts(label)) return;
        long observedAt = SystemClock.elapsedRealtime();
        // Candidate slots are bounded, but disconnected vanished labels must not pin them forever.
        peers.values().removeIf(candidate -> candidate.link == null && observedAt - candidate.seen > SEEN_MS);
        String address = result.getDevice().getAddress();
        Peer peer = peers.get(label);
        if (peer == null) {
            if (peers.size() >= MAX_LINKS) return;
            peer = new Peer(label);
            peers.put(label, peer);
            emit("ble_peer_seen", label, "untrusted_lab_label", 0, 0);
        }
        if (peer.link != null && !peer.address.equals(address)) return;
        peer.address = address;
        peer.device = result.getDevice();
        peer.seen = SystemClock.elapsedRealtime();
        maybeConnect(peer);
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void maybeConnect(Peer peer) {
        if (closed.get() || peers.get(peer.label) != peer || peer.link != null || links.size() >= MAX_LINKS
                || peer.attempts >= MAX_ATTEMPTS || config.label.compareTo(peer.label) >= 0
                || SystemClock.elapsedRealtime() < peer.retryAt
                || SystemClock.elapsedRealtime() - peer.seen > SEEN_MS) return;
        Link link = new Link(peer, true);
        attach(link);
        emit("ble_connecting", peer.label, "central", 0, 0);
        link.gatt = peer.device.connectGatt(context, false, clientCallback(link),
                BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler);
        if (link.gatt == null) failLink(link, "connect_rejected");
    }

    private void attach(Link link) {
        link.peer.attempts++;
        link.peer.link = link;
        links.put(link.address, link);
        handler.postDelayed(() -> guarded(() -> {
            if (live(link) && !link.transportReady) failLink(link, "gatt_setup_timeout");
        }), SETUP_MS);
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private BluetoothGattCallback clientCallback(Link link) {
        return new BluetoothGattCallback() {
            @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
                dispatch(() -> {
                    if (!live(link) || gatt != link.gatt) return;
                    if (status != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                        failLink(link, "gatt_connection_" + status + "_" + state); return;
                    }
                    if (state == BluetoothProfile.STATE_CONNECTED && "connecting".equals(link.stage)) {
                        link.stage = "mtu";
                        if (!gatt.requestMtu(BleChunks.MAX_MTU)) discover(link);
                    }
                });
            }
            @Override public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
                dispatch(() -> {
                    if (!live(link) || gatt != link.gatt) return;
                    if (status == BluetoothGatt.GATT_SUCCESS) link.mtu = validMtu(mtu);
                    if ("mtu".equals(link.stage)) discover(link);
                });
            }
            @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                dispatch(() -> {
                    if (!live(link) || gatt != link.gatt || !"services".equals(link.stage)) return;
                    BluetoothGattService service = status == BluetoothGatt.GATT_SUCCESS ? gatt.getService(SERVICE) : null;
                    link.rx = service == null ? null : service.getCharacteristic(RX);
                    link.tx = service == null ? null : service.getCharacteristic(TX);
                    BluetoothGattDescriptor cccd = link.tx == null ? null : link.tx.getDescriptor(CCCD);
                    if (link.rx == null || link.tx == null || cccd == null
                            || (link.rx.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                            || (link.tx.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0
                            || !gatt.setCharacteristicNotification(link.tx, true)) {
                        failLink(link, "gatt_service_or_indication_missing"); return;
                    }
                    link.stage = "cccd";
                    if (!writeDescriptor(gatt, cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE))
                        failLink(link, "cccd_write_rejected");
                });
            }
            @Override public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                dispatch(() -> {
                    if (!live(link) || gatt != link.gatt || !"cccd".equals(link.stage)
                            || !CCCD.equals(descriptor.getUuid())) return;
                    if (status != BluetoothGatt.GATT_SUCCESS) failLink(link, "cccd_write_" + status);
                    else openCore(link);
                });
            }
            @Override public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                dispatch(() -> {
                    if (!live(link) || gatt != link.gatt || !RX.equals(characteristic.getUuid())) return;
                    if (link.pending == null) { failLink(link, "unexpected_write_ack"); return; }
                    complete(link, status);
                });
            }
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
                byte[] copy = value == null ? null : value.clone();
                dispatch(() -> { if (live(link) && gatt == link.gatt && TX.equals(characteristic.getUuid())) ingress(link, copy); });
            }
            @SuppressWarnings("deprecation")
            @Override public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                onCharacteristicChanged(gatt, characteristic, characteristic.getValue());
            }
        };
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void discover(Link link) {
        link.stage = "services";
        if (!link.gatt.discoverServices()) failLink(link, "service_discovery_rejected");
    }

    private final BluetoothGattServerCallback serverCallback = new BluetoothGattServerCallback() {
        @Override public void onServiceAdded(int status, BluetoothGattService service) {
            dispatch(() -> {
                if (!SERVICE.equals(service.getUuid()) || serviceAdded) return;
                if (status != BluetoothGatt.GATT_SUCCESS) failAdapter("gatt_add_service_" + status);
                else startDiscovery();
            });
        }
        @Override public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
            dispatch(() -> serverConnection(device, status, state));
        }
        @Override public void onMtuChanged(BluetoothDevice device, int mtu) {
            dispatch(() -> { Link link = serverLink(device); if (link != null) link.mtu = validMtu(mtu); });
        }
        @Override public void onDescriptorWriteRequest(BluetoothDevice device, int request, BluetoothGattDescriptor descriptor,
                boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            byte[] copy = value == null ? null : value.clone();
            dispatch(() -> {
                Link link = serverLink(device);
                boolean valid = link != null && CCCD.equals(descriptor.getUuid())
                        && TX.equals(descriptor.getCharacteristic().getUuid()) && !prepared && offset == 0 && responseNeeded;
                boolean enable = valid && Arrays.equals(copy, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                boolean disable = valid && Arrays.equals(copy, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                boolean accepted = enable || disable;
                if (responseNeeded) respond(device, request, accepted ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                if (!accepted) { if (link != null) failLink(link, "invalid_cccd_write"); return; }
                if (disable) { failLink(link, "cccd_disabled"); return; }
                if (!link.subscribed) { link.subscribed = true; openCore(link); }
            });
        }
        @Override public void onDescriptorReadRequest(BluetoothDevice device, int request, int offset, BluetoothGattDescriptor descriptor) {
            dispatch(() -> {
                Link link = serverLink(device);
                boolean valid = link != null && offset == 0 && CCCD.equals(descriptor.getUuid());
                respond(device, request, valid ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0,
                        valid ? (link.subscribed ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) : null);
            });
        }
        @Override public void onCharacteristicWriteRequest(BluetoothDevice device, int request, BluetoothGattCharacteristic characteristic,
                boolean prepared, boolean responseNeeded, int offset, byte[] value) {
            byte[] copy = value == null ? null : value.clone();
            dispatch(() -> {
                Link link = serverLink(device);
                boolean valid = link != null && RX.equals(characteristic.getUuid()) && !prepared && responseNeeded && offset == 0;
                boolean accepted = valid && ingress(link, copy);
                if (responseNeeded) respond(device, request, accepted ? BluetoothGatt.GATT_SUCCESS : BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null);
                if (!accepted && link != null) failLink(link, "invalid_or_backpressured_write");
            });
        }
        @Override public void onCharacteristicReadRequest(BluetoothDevice device, int request, int offset, BluetoothGattCharacteristic characteristic) {
            dispatch(() -> respond(device, request, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null));
        }
        @Override public void onExecuteWrite(BluetoothDevice device, int request, boolean execute) {
            dispatch(() -> respond(device, request, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null));
        }
        @Override public void onNotificationSent(BluetoothDevice device, int status) {
            dispatch(() -> {
                // Keep the actual issuing Link, including a closed-link tombstone.
                // Resolving by current address could acknowledge a reconnected peer's
                // different operation when this callback belongs to the old link.
                Link link = indicating;
                if (link == null || !link.address.equals(device.getAddress())) return;
                indicating = null;
                if (live(link)) complete(link, status);
                pumpServer();
            });
        }
    };

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void serverConnection(BluetoothDevice device, int status, int state) {
        String address = device.getAddress();
        Link current = links.get(address);
        // Some stacks report the shared ACL connection to both registered GATT roles.
        if (current != null && current.client) return;
        if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
            if (current != null) failLink(current, "server_connection_" + status + "_" + state);
            return;
        }
        if (state != BluetoothProfile.STATE_CONNECTED || current != null) return;
        Peer peer = null;
        for (Peer candidate : peers.values()) if (address.equals(candidate.address)) { peer = candidate; break; }
        if (peer == null || peer.link != null || peer.attempts >= MAX_ATTEMPTS || links.size() >= MAX_LINKS
                || (indicating != null && indicating.ended && indicating.address.equals(address))
                || peer.label.compareTo(config.label) >= 0 || SystemClock.elapsedRealtime() - peer.seen > SEEN_MS) {
            server.cancelConnection(device);
            emit("ble_connection_rejected", peer == null ? "" : peer.label, "label_role_or_link_bound", 0, 0);
            return;
        }
        Link link = new Link(peer, false);
        attach(link);
        emit("ble_connecting", peer.label, "peripheral", 0, 0);
    }

    private Link serverLink(BluetoothDevice device) {
        Link link = links.get(device.getAddress());
        return link != null && !link.client && live(link) ? link : null;
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void respond(BluetoothDevice device, int request, int status, int offset, byte[] value) {
        if (server != null && !server.sendResponse(device, request, status, offset, value)) {
            Link link = serverLink(device);
            if (link != null) failLink(link, "gatt_response_rejected");
        }
    }

    private void openCore(Link link) {
        if (!live(link) || link.transportReady) return;
        link.transportReady = true;
        link.stage = "core";
        CoreBridge bridge = new CoreBridge(config.corePort, new CoreBridge.Sink() {
            @Override public void send(byte[] bytes) throws IOException { sendToRadio(link, bytes); }
            @Override public void close() { dispatch(() -> failLink(link, "core_closed")); }
        }, () -> dispatch(() -> failLink(link, "core_closed")));
        link.core = bridge;
        try {
            coreStarts.execute(() -> {
                try {
                    bridge.start();
                    dispatch(() -> {
                        if (!live(link)) { bridge.close(); return; }
                        link.coreStarted = true;
                        link.stage = "ready";
                        byte[] early = link.early.toByteArray();
                        link.early.reset();
                        for (int offset = 0; offset < early.length; offset += BleChunks.MAX_CORE_CHUNK) {
                            if (!bridge.receive(Arrays.copyOfRange(early, offset, Math.min(early.length, offset + BleChunks.MAX_CORE_CHUNK)))) {
                                failLink(link, "core_ingress_backpressure"); return;
                            }
                        }
                        emit("ble_link_up", link.peer.label, link.client ? "central" : "peripheral", link.txBytes, link.rxBytes);
                    });
                } catch (IOException failure) { dispatch(() -> failLink(link, "core_connect_failed")); }
            });
        } catch (RuntimeException rejected) { failLink(link, "core_start_capacity"); }
    }

    private boolean ingress(Link link, byte[] bytes) {
        if (!live(link) || !link.transportReady || bytes == null || bytes.length == 0
                || bytes.length > BleChunks.payloadSize(link.mtu)) {
            if (live(link)) failLink(link, "invalid_ingress_or_mtu");
            return false;
        }
        if (!link.coreStarted) {
            if (link.early.size() + bytes.length > MAX_EARLY_BYTES) { failLink(link, "early_ingress_bound"); return false; }
            link.early.write(bytes, 0, bytes.length);
        } else if (!link.core.receive(bytes)) { failLink(link, "core_ingress_backpressure"); return false; }
        link.rxBytes += bytes.length;
        emit("ble_rx", link.peer.label, "att_bytes", link.txBytes, link.rxBytes);
        return true;
    }

    /** Called only by the bridge worker. Never wait for a GATT callback on Handler. */
    private void sendToRadio(Link link, byte[] bytes) throws IOException {
        if (Looper.myLooper() == handler.getLooper()) throw new IOException("ble_send_on_handler");
        boolean acquired = false;
        try {
            acquired = link.sender.tryAcquire(OP_MS, TimeUnit.MILLISECONDS);
            if (!acquired) throw new IOException("ble_sender_timeout");
            BleChunks chunks = new BleChunks(bytes, link.mtu);
            while (chunks.hasNext()) {
                Operation operation = new Operation(chunks.next());
                if (!handler.post(() -> guarded(() -> enqueue(link, operation)))) throw new IOException("ble_handler_stopped");
                if (!operation.done.await(OP_MS, TimeUnit.MILLISECONDS)) {
                    dispatch(() -> failLink(link, "att_ack_timeout"));
                    throw new IOException("ble_att_ack_timeout");
                }
                if (operation.error != null) throw new IOException(operation.error);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            dispatch(() -> failLink(link, "sender_interrupted"));
            throw new IOException("ble_sender_interrupted", interrupted);
        } catch (IllegalArgumentException invalid) {
            dispatch(() -> failLink(link, "core_chunk_bound"));
            throw new IOException("ble_core_chunk_bound", invalid);
        } finally { if (acquired) link.sender.release(); }
    }

    private void enqueue(Link link, Operation operation) {
        if (!live(link) || !link.transportReady) { operation.finish("ble_link_closed"); return; }
        if (SystemClock.elapsedRealtime() >= operation.deadline || link.queue.size() >= MAX_QUEUED_OPS
                || link.queuedBytes + operation.bytes.length > MAX_QUEUED_BYTES) {
            operation.finish("ble_queue_bound_or_timeout"); failLink(link, "outbound_queue_bound_or_timeout"); return;
        }
        link.queue.addLast(operation);
        link.queuedBytes += operation.bytes.length;
        if (link.client) pumpClient(link); else pumpServer();
    }

    private Operation next(Link link) {
        if (link.pending != null || link.queue.isEmpty()) return null;
        Operation operation = link.queue.removeFirst();
        link.queuedBytes -= operation.bytes.length;
        if (SystemClock.elapsedRealtime() >= operation.deadline || operation.bytes.length > BleChunks.payloadSize(link.mtu)) {
            operation.finish("ble_queued_timeout_or_mtu"); failLink(link, "queued_timeout_or_mtu"); return null;
        }
        link.pending = operation;
        operation.timeout = () -> guarded(() -> {
            if (live(link) && link.pending == operation) failLink(link, "att_ack_timeout");
        });
        handler.postDelayed(operation.timeout, OP_MS);
        return operation;
    }

    private void pumpClient(Link link) {
        Operation operation = next(link);
        if (operation != null && !writeCharacteristic(link.gatt, link.rx, operation.bytes))
            failLink(link, "att_write_rejected");
    }

    private void pumpServer() {
        if (indicating != null || server == null) return;
        // Rotate after the last submitted peer so a continuously busy neighbor cannot
        // monopolize the single outstanding server indication. Snapshot tolerates cleanup.
        ArrayList<Link> candidates = new ArrayList<>(links.values());
        int start = 0;
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).address.equals(lastIndicatedAddress)) start = i + 1;
        }
        for (int offset = 0; offset < candidates.size(); offset++) {
            Link link = candidates.get((start + offset) % candidates.size());
            if (!live(link) || link.client || !link.subscribed || !link.transportReady) continue;
            Operation operation = next(link);
            // Expiry can fail this link and reenter pumpServer from failLink.
            // Preserve the indication that the nested pump already submitted.
            if (indicating != null) return;
            if (operation == null) continue;
            indicating = link;
            lastIndicatedAddress = link.address;
            if (!indicate(link.device, operation.bytes)) {
                // Initiation was rejected: no callback is owed for this operation.
                indicating = null;
                failLink(link, "att_indication_rejected");
            }
            return;
        }
    }

    private void complete(Link link, int status) {
        Operation operation = link.pending;
        if (operation == null) { failLink(link, "unexpected_att_ack"); return; }
        if (status != BluetoothGatt.GATT_SUCCESS) { failLink(link, "att_ack_" + status); return; }
        link.pending = null;
        handler.removeCallbacks(operation.timeout);
        link.txBytes += operation.bytes.length;
        operation.finish(null);
        emit("ble_tx", link.peer.label, "att_ack", link.txBytes, link.rxBytes);
        if (link.client) pumpClient(link);
    }

    @SuppressWarnings("deprecation")
    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private static boolean writeDescriptor(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, byte[] bytes) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeDescriptor(descriptor, bytes) == BluetoothStatusCodes.SUCCESS;
        return descriptor.setValue(bytes) && gatt.writeDescriptor(descriptor);
    }

    @SuppressWarnings("deprecation")
    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private static boolean writeCharacteristic(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] bytes) {
        if (Build.VERSION.SDK_INT >= 33) return gatt.writeCharacteristic(characteristic, bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        return characteristic.setValue(bytes) && gatt.writeCharacteristic(characteristic);
    }

    @SuppressWarnings("deprecation")
    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private boolean indicate(BluetoothDevice device, byte[] bytes) {
        if (Build.VERSION.SDK_INT >= 33) return server.notifyCharacteristicChanged(device, serverTx, true, bytes) == BluetoothStatusCodes.SUCCESS;
        return serverTx.setValue(bytes) && server.notifyCharacteristicChanged(device, serverTx, true);
    }

    private boolean live(Link link) { return !closed.get() && !link.ended && link.peer.link == link; }
    private static int validMtu(int mtu) {
        if (mtu < BleChunks.DEFAULT_MTU || mtu > BleChunks.MAX_MTU) throw new IllegalArgumentException("invalid_negotiated_mtu");
        return mtu;
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void failLink(Link link, String reason) {
        if (link.ended) return;
        link.ended = true;
        links.remove(link.address);
        if (link.peer.link == link) link.peer.link = null;
        if (indicating == link) {
            // An accepted indication remains globally in flight until its callback,
            // even if the connection closes. Never reuse its address for an ACK.
            handler.postDelayed(() -> guarded(() -> {
                if (indicating == link) failAdapter("indication_cancellation_timeout");
            }), OP_MS);
        }
        if (link.pending != null) {
            handler.removeCallbacks(link.pending.timeout);
            link.pending.finish("ble_" + reason);
            link.pending = null;
        }
        for (Operation operation : link.queue) operation.finish("ble_" + reason);
        link.queue.clear(); link.queuedBytes = 0; link.early.reset();
        if (link.core != null) link.core.close();
        if (link.gatt != null) {
            try { link.gatt.disconnect(); } catch (RuntimeException ignored) { }
            try { link.gatt.close(); } catch (RuntimeException ignored) { }
        } else if (server != null) {
            try { server.cancelConnection(link.device); } catch (RuntimeException ignored) { }
        }
        emit("ble_link_down", link.peer.label, reason, link.txBytes, link.rxBytes);
        if (!closed.get()) {
            pumpServer();
            if (link.peer.attempts < MAX_ATTEMPTS && config.label.compareTo(link.peer.label) < 0) {
                long delay = 1000L << Math.min(link.peer.attempts - 1, 3);
                link.peer.retryAt = SystemClock.elapsedRealtime() + delay;
                emit("ble_retry", link.peer.label, "attempt_" + (link.peer.attempts + 1), link.txBytes, link.rxBytes);
                handler.postDelayed(() -> guarded(() -> maybeConnect(link.peer)), delay);
            } else if (link.peer.attempts >= MAX_ATTEMPTS) emit("ble_retry_exhausted", link.peer.label, "attempt_bound", link.txBytes, link.rxBytes);
        }
    }

    private void failAdapter(String reason) {
        emit("ble_error", "", reason, 0, 0);
        synchronized (callbackGate) { closed.set(true); }
        closeOnHandler();
    }

    private void dispatch(Runnable action) {
        synchronized (callbackGate) {
            if (closed.get()) return;
            if (!callbackBudget.acquire()) { callbackOverflow(); return; }
            if (!handler.post(() -> {
                try {
                    // Once an ACK has been dropped, stop processing this queue and
                    // close every link rather than credit a later operation.
                    if (!callbackBudget.overflowed()) guarded(action);
                } finally { callbackBudget.release(); }
            })) callbackBudget.release();
        }
    }

    private void callbackOverflow() {
        synchronized (callbackGate) {
            if (closed.get() || !callbackBudget.overflow()) return;
            // At most one overflow runnable exists in addition to the128 slots.
            handler.post(() -> { if (!closed.get()) failAdapter("callback_budget"); });
        }
    }
    private void guarded(Runnable action) {
        if (closed.get()) return;
        try { action.run(); }
        catch (SecurityException denied) { failAdapter("permission_denied"); }
        catch (RuntimeException failure) { failAdapter("radio_exception_" + failure.getClass().getSimpleName()); }
    }
    private void emit(String event, String peer, String reason, long tx, long rx) { events.emit(event, peer, reason, tx, rx); }

    @Override public void close() {
        synchronized (callbackGate) {
            if (!closed.compareAndSet(false, true)) return;
        }
        if (Looper.myLooper() == handler.getLooper()) closeOnHandler();
        else handler.post(this::closeOnHandler);
    }

    // Permission denial is caught by guarded() or this cleanup method; admission checks run at start.
    @android.annotation.SuppressLint("MissingPermission")
    private void closeOnHandler() {
        for (Link link : new ArrayList<>(links.values())) failLink(link, "adapter_closed");
        if (scanner != null && scanning) try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) { }
        if (advertiser != null && advertising) try { advertiser.stopAdvertising(advertiseCallback); } catch (RuntimeException ignored) { }
        scanning = false; advertising = false;
        if (server != null) { try { server.close(); } catch (RuntimeException ignored) { } server = null; }
        indicating = null;
        coreStarts.shutdownNow();
        peers.clear();
        emit("ble_stopped", "", "closed", 0, 0);
    }

    private static final class Peer {
        final String label;
        String address = "";
        BluetoothDevice device;
        Link link;
        int attempts;
        long seen, retryAt;
        Peer(String label) { this.label = label; }
    }

    private static final class Link {
        final Peer peer;
        final boolean client;
        final String address;
        final BluetoothDevice device;
        final ArrayDeque<Operation> queue = new ArrayDeque<>();
        final ByteArrayOutputStream early = new ByteArrayOutputStream();
        final Semaphore sender = new Semaphore(1);
        volatile int mtu = BleChunks.DEFAULT_MTU;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic rx, tx;
        CoreBridge core;
        Operation pending;
        String stage = "connecting";
        boolean subscribed, transportReady, coreStarted, ended;
        int queuedBytes;
        long txBytes, rxBytes;
        Link(Peer peer, boolean client) {
            this.peer = peer; this.client = client;
            this.address = peer.address; this.device = peer.device;
        }
    }

    private static final class Operation {
        final byte[] bytes;
        final long deadline = SystemClock.elapsedRealtime() + OP_MS;
        final CountDownLatch done = new CountDownLatch(1);
        Runnable timeout;
        volatile String error;
        Operation(byte[] bytes) { this.bytes = bytes; }
        void finish(String error) { this.error = error; done.countDown(); }
    }
}
