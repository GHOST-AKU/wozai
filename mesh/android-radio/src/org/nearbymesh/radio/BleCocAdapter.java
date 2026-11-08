package org.nearbymesh.radio;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;
import java.io.*;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** API29+ LE-only credit-based sockets; discovery hints never confer identity trust. */
@android.annotation.TargetApi(29)
public final class BleCocAdapter implements RadioAdapter {
    static final UUID SERVICE = UUID.fromString("eed3c891-5c83-4f9c-a88d-94bc822c71e0");
    private final Context context;
    private final Handler handler;
    private final RadioConfig config;
    private final RadioEvents events;
    private final AtomicBoolean closed = new AtomicBoolean(), disposed = new AtomicBoolean();
    private final CallbackBudget callbacks = new CallbackBudget(128);
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final CocStreams streams;
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothServerSocket listener;
    private Future<?> acceptTask;
    private ScanCallback scan;
    private AdvertiseCallback advertisement;
    private volatile long epoch;
    private long scanEpoch, advertEpoch;
    private int listenerFailures, scanFailures, advertFailures;
    private boolean started;
    private CocRuntime.Ticket bindDeadline;

    public BleCocAdapter(Context context, Handler handler, RadioConfig config, RadioEvents events) {
        this.context = context.getApplicationContext(); this.handler = handler;
        this.config = config; this.events = events;
        streams = new CocStreams(config, new CocStreams.Observer() {
            public void event(String event, String peer, String reason, long tx, long rx) {
                post(() -> {
                    emit(event, peer, reason, tx, rx);
                    if ("ble_link_down".equals(event)) {
                        Peer p = peers.get(peer);
                        if (p != null && config.label.compareTo(peer) < 0) retry(p);
                    }
                });
            }
            public void capacityChanged() { }
            public void progress(CocStreams.Link link) {
                if (!link.progressPending.compareAndSet(false, true)) return;
                if (!handler.post(() -> {
                    try {
                        if (!closed.get() && !link.closed.get()) {
                            emit("ble_tx", link.peer, "socket_write", link.tx.get(), link.rx.get());
                            emit("ble_rx", link.peer, "socket_bytes", link.tx.get(), link.rx.get());
                        }
                    } finally { link.progressPending.set(false); }
                })) link.progressPending.set(false);
            }
        });
    }

    @android.annotation.SuppressLint("MissingPermission") // Checks precede platform access; start failure is propagated to the owner.
    @Override public void start() throws Exception {
        if (started || closed.get() || !"ble".equals(config.mode)) throw new IOException("coc_start_state");
        if (Build.VERSION.SDK_INT < 29) throw new IOException("coc_requires_api29");
        permissions();
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (!available()) throw new IOException("coc_radio_unavailable");
        scanner = adapter.getBluetoothLeScanner(); advertiser = adapter.getBluetoothLeAdvertiser();
        if (scanner == null || advertiser == null) throw new IOException("coc_discovery_unavailable");
        started = true;
        post(this::bind);
    }
    private void permissions() {
        String[] names = Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String name : names) if (context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("permission_denied");
    }
    @android.annotation.SuppressLint("MissingPermission") // Admission checks above; revocation is handled by guarded/post and workers.
    private boolean available() { return adapter != null && adapter.isEnabled(); }

    @android.annotation.SuppressLint("MissingPermission") // Called only after permissions(); worker handles revocation.
    private void bind() {
        if (closed.get()) return;
        final long token = ++epoch;
        bindDeadline = CocRuntime.TIME.after(15000, () -> post(() -> {
            if (epoch == token && listener == null) fatal("listener_bind_timeout");
        }));
        try {
            CocRuntime.BIND.execute(() -> {
                BluetoothServerSocket socket = null;
                try {
                    socket = adapter.listenUsingInsecureL2capChannel();
                    final BluetoothServerSocket owned = socket;
                    if (!handler.post(() -> guarded(() -> {
                        if (closed.get() || epoch != token) { closeServer(owned); return; }
                        if (bindDeadline != null) bindDeadline.cancel();
                        if (owned.getPsm() < 1 || owned.getPsm() > 65535) { closeServer(owned); repair(token, "invalid_psm"); return; }
                        listener = owned;
                        startScan(); advertise(token, owned.getPsm()); accept(token, owned);
                    }))) closeServer(owned);
                } catch (SecurityException denied) {
                    closeServer(socket); post(() -> { if (epoch == token) fatal("permission_denied"); });
                } catch (IOException | RuntimeException error) {
                    closeServer(socket); post(() -> repair(token, "listener_bind_failed"));
                }
            });
        } catch (RejectedExecutionException busy) { repair(token, "listener_worker_busy"); }
    }
    private void accept(long token, BluetoothServerSocket server) {
        try {
            acceptTask = CocRuntime.ACCEPT.submit(() -> {
                try {
                    while (!closed.get() && epoch == token) {
                        streams.awaitCapacity();
                        if (closed.get() || epoch != token) break;
                        BluetoothSocket socket = server.accept();
                        if (closed.get() || epoch != token) { closeSocket(socket); break; }
                        streams.accept(new Endpoint(socket));
                    }
                } catch (IOException | InterruptedException | RuntimeException error) {
                    post(() -> repair(token, "listener_accept_failed"));
                }
            });
        } catch (RejectedExecutionException busy) { repair(token, "accept_worker_busy"); }
    }
    private void repair(long token, String reason) {
        if (closed.get() || epoch != token) return;
        ++epoch;
        if (bindDeadline != null) bindDeadline.cancel();
        stopAdvertisement(); closeServer(listener); listener = null;
        if (acceptTask != null) { acceptTask.cancel(true); acceptTask = null; }
        long delay = backoff(listenerFailures++);
        emit("ble_listener_recovery", "", reason, 0, 0);
        if (listenerFailures >= 4) listenerFailures = 0;
        final long retired = epoch;
        handler.postDelayed(() -> { if (!closed.get() && epoch == retired) bind(); }, delay);
    }
    private static long backoff(int failures) { return failures >= 3 ? 60000 : 1000L << failures; }

    @android.annotation.SuppressLint("MissingPermission") // Every call is guarded; no permission request or Bluetooth enable here.
    private void startScan() {
        if (closed.get() || scan != null) return;
        final long token = ++scanEpoch;
        scan = new ScanCallback() {
            public void onScanResult(int type, ScanResult result) { post(() -> { if (token == scanEpoch) observed(result); }); }
            public void onBatchScanResults(List<ScanResult> results) {
                if (results.size() > 32) { post(() -> fatal("scan_batch_bound")); return; }
                final List<ScanResult> copy = new ArrayList<>(results);
                post(() -> { if (token == scanEpoch) for (ScanResult result : copy) observed(result); });
            }
            public void onScanFailed(int code) { post(() -> {
                if (token != scanEpoch) return;
                if (code == 4) { emit("ble_scan_failed", "", "scan_failed_" + code, 0, 0); fatal("scan_unsupported"); return; }
                stopScan();
                long delay = code == 6 ? 30000 : Math.max(5000, backoff(scanFailures++));
                if (scanFailures >= 4) scanFailures = 0;
                long retired = scanEpoch;
                emit("ble_scan_recovery", "", "scan_failed_" + code, 0, 0);
                handler.postDelayed(() -> { if (!closed.get() && retired == scanEpoch) guarded(BleCocAdapter.this::startScan); }, delay);
            }); }
        };
        scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), scan);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void stopScan() {
        ++scanEpoch;
        if (scan != null && scanner != null) try { scanner.stopScan(scan); } catch (RuntimeException ignored) { }
        scan = null;
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void advertise(long token, int psm) {
        if (closed.get() || epoch != token) return;
        stopAdvertisement(); final long attempt = ++advertEpoch;
        advertisement = new AdvertiseCallback() {
            public void onStartSuccess(AdvertiseSettings settings) { post(() -> {
                if (token == epoch && attempt == advertEpoch) emit("ble_ready", "", "le_coc", 0, 0);
            }); }
            public void onStartFailure(int code) { post(() -> {
                if (token != epoch || attempt != advertEpoch) return;
                if (code == 1 || code == 5) { emit("ble_advertise_failed", "", "advertise_failed_" + code, 0, 0); fatal("advertisement_unsupported"); return; }
                stopAdvertisement(); long retired = advertEpoch;
                long delay = backoff(advertFailures++); if (advertFailures >= 4) advertFailures = 0;
                emit("ble_advertise_recovery", "", "advertise_failed_" + code, 0, 0);
                handler.postDelayed(() -> { if (!closed.get() && token == epoch && retired == advertEpoch) guarded(() -> advertise(token, psm)); }, delay);
            }); }
        };
        advertiser.startAdvertising(new AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setTimeout(0).build(),
            new AdvertiseData.Builder().addServiceUuid(new ParcelUuid(SERVICE)).setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build(),
            new AdvertiseData.Builder().addServiceData(new ParcelUuid(SERVICE), CocProtocol.advertisement(config.label, psm))
                .setIncludeDeviceName(false).setIncludeTxPowerLevel(false).build(), advertisement);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void stopAdvertisement() {
        ++advertEpoch;
        if (advertisement != null && advertiser != null) try { advertiser.stopAdvertising(advertisement); } catch (RuntimeException ignored) { }
        advertisement = null;
    }
    private void observed(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        CocProtocol.Offer offer = CocProtocol.parse(record == null ? null : record.getServiceData(new ParcelUuid(SERVICE)), config);
        if (offer == null) return;
        long now = CocRuntime.TIME.now();
        peers.values().removeIf(p -> now - p.seen > 30000 && now >= p.retryAt && !streams.has(p.label));
        Peer peer = peers.get(offer.label);
        if (peer == null) {
            if (peers.size() >= 16) return;
            peer = new Peer(offer.label); peers.put(peer.label, peer);
            emit("ble_peer_seen", peer.label, "le_coc", 0, 0);
        }
        peer.device = result.getDevice(); peer.psm = offer.psm; peer.seen = now;
        dial(peer);
    }
    @android.annotation.SuppressLint("MissingPermission") // Socket creation and IO are worker-side with permission failure cleanup.
    private void dial(Peer peer) {
        long now = CocRuntime.TIME.now();
        if (closed.get() || peers.get(peer.label) != peer || streams.has(peer.label) || config.label.compareTo(peer.label) >= 0
                || now - peer.seen > 30000 || now < peer.retryAt) return;
        if (peer.attempts >= 4) peer.attempts = 0;
        final BluetoothDevice device = peer.device; final int psm = peer.psm;
        if (streams.dial(peer.label, () -> new Endpoint(device.createInsecureL2capChannel(psm)))) {
            peer.attempts++; peer.retryAt = now + 1000;
        }
    }
    private void retry(Peer peer) {
        long delay = peer.attempts >= 4 ? 60000 : 1000L << Math.max(0, peer.attempts - 1);
        peer.retryAt = Math.max(peer.retryAt, CocRuntime.TIME.now() + delay);
        long retryAt = peer.retryAt;
        emit("ble_retry", peer.label, peer.attempts >= 4 ? "cooldown" : "backoff", 0, 0);
        handler.postDelayed(() -> { if (!closed.get() && peers.get(peer.label) == peer && peer.retryAt == retryAt) guarded(() -> dial(peer)); }, delay);
    }
    private void post(Runnable action) {
        if (closed.get()) return;
        if (!callbacks.acquire()) {
            if (callbacks.overflow() && !handler.post(() -> fatal("callback_bound"))) fatal("callback_bound");
            return;
        }
        if (!handler.post(() -> { try { if (!closed.get()) guarded(action); } finally { callbacks.release(); } })) {
            callbacks.release(); fatal("handler_unavailable");
        }
    }
    private void guarded(Runnable action) {
        try { action.run(); }
        catch (SecurityException denied) { fatal("permission_denied"); }
        catch (RuntimeException error) { fatal("radio_operation_failed"); }
    }
    private void fatal(String reason) {
        if (closed.get()) return;
        emit("terminal_failure", "", reason, 0, 0); close();
    }
    private void emit(String event, String peer, String reason, long tx, long rx) { events.emit(event, peer, reason, tx, rx); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        if (!handler.post(this::cleanup)) cleanup();
    }
    private void cleanup() {
        if (!disposed.compareAndSet(false, true)) return;
        ++epoch;
        if (bindDeadline != null) bindDeadline.cancel();
        stopAdvertisement(); stopScan(); closeServer(listener); listener = null;
        if (acceptTask != null) { acceptTask.cancel(true); acceptTask = null; }
        streams.close(); emit("ble_stopped", "", "closed", 0, 0);
    }
    private static void closeServer(BluetoothServerSocket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    private static void closeSocket(BluetoothSocket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    @android.annotation.SuppressLint("MissingPermission") // IO jobs handle permission revocation and always release the socket.
    private static final class Endpoint implements CocStreams.Socket {
        final BluetoothSocket socket;
        Endpoint(BluetoothSocket socket) { this.socket = socket; }
        public void connect() throws IOException { socket.connect(); }
        public InputStream input() throws IOException { return socket.getInputStream(); }
        public OutputStream output() throws IOException { return socket.getOutputStream(); }
        public void close() { closeSocket(socket); }
    }
    private static final class Peer {
        final String label; BluetoothDevice device; int psm, attempts; long seen, retryAt;
        Peer(String label) { this.label = label; }
    }
}
