package dev.ghost.nearbyim.transport;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import dev.ghost.nearbyim.core.StreamConnection;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Classic Bluetooth RFCOMM; the OS performs pairing and link encryption. */
@SuppressLint("MissingPermission") // MainActivity gates runtime permissions; operations also handle revocation.
public final class BluetoothTransport {
    private static final UUID SERVICE = UUID.fromString("90c649e1-c095-4b22-8bc3-35e4c9c7b372");
    private final Context context;
    private final BluetoothAdapter adapter;
    private final TransportListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicInteger epoch = new AtomicInteger();
    private final Object lock = new Object();
    private BluetoothServerSocket server;
    private BluetoothSocket pending;
    private boolean registered;

    public BluetoothTransport(Context context, TransportListener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public boolean available() { return adapter != null; }
    public boolean enabled() { return adapter != null && adapter.isEnabled(); }
    public void start() {
        stop(); final int run = epoch.get();
        try {
            if (!enabled()) { listener.onError(Peer.BLUETOOTH, "请先打开蓝牙", true); return; }
            IntentFilter filter = new IntentFilter(); filter.addAction(BluetoothDevice.ACTION_FOUND); filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            registered = true;
        } catch (RuntimeException e) { listener.onError(Peer.BLUETOOTH, "缺少蓝牙权限，请在应用设置中允许附近设备", true); return; }
        worker.execute(() -> {
            try {
                BluetoothServerSocket opened = adapter.listenUsingRfcommWithServiceRecord("NearbyIM", SERVICE);
                synchronized (lock) { if (run != epoch.get()) { opened.close(); return; } server = opened; }
                main.post(() -> { if (run == epoch.get()) listener.onListening(Peer.BLUETOOTH, "蓝牙接收已开启 · 对方搜索前请允许被发现"); });
                while (run == epoch.get()) {
                    BluetoothSocket socket = opened.accept();
                    main.post(() -> { if (run == epoch.get()) listener.onConnection(Peer.BLUETOOTH, wrap(socket), true); else close(socket); });
                }
            } catch (IOException | RuntimeException e) { main.post(() -> { if (run == epoch.get()) { stop(); listener.onError(Peer.BLUETOOTH, "蓝牙接收已停止，请检查蓝牙开关和权限", true); } }); }
        });
    }
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressWarnings("deprecation")
        public void onReceive(Context ignored, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) report(device);
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                stop(); listener.onError(Peer.BLUETOOTH, "蓝牙已关闭", true);
            }
        }
    };
    private void report(BluetoothDevice device) {
        try {
            String name = device.getName(); if (name == null || name.trim().isEmpty()) name = "未命名设备";
            String mac = device.getAddress(); String detail = device.getBondState() == BluetoothDevice.BOND_BONDED ? "已配对 · " + mac : "未配对 · " + mac;
            listener.onPeer(new Peer(Peer.BLUETOOTH, "bt:" + mac, name, detail, null, 0, mac));
        } catch (SecurityException e) { listener.onError(Peer.BLUETOOTH, "蓝牙权限已撤销，请重新授权", false); }
    }
    public void scan() {
        try {
            if (!enabled()) { listener.onError(Peer.BLUETOOTH, "请先打开蓝牙", false); return; }
            adapter.cancelDiscovery(); for (BluetoothDevice device : adapter.getBondedDevices()) report(device);
            if (!adapter.startDiscovery()) listener.onError(Peer.BLUETOOTH, "搜索没有启动，可尝试已配对设备；Android 11 及以前需打开系统定位", false);
        } catch (RuntimeException e) { listener.onError(Peer.BLUETOOTH, "蓝牙搜索失败，请检查附近设备或定位权限", false); }
    }
    public void connect(String address) {
        final int run = epoch.get();
        worker.execute(() -> {
            BluetoothSocket socket = null; ScheduledFuture<?> timeout = null;
            try {
                adapter.cancelDiscovery();
                socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SERVICE);
                synchronized (lock) { if (run != epoch.get()) { socket.close(); return; } pending = socket; }
                final BluetoothSocket connecting = socket;
                timeout = timer.schedule(() -> close(connecting), 30, TimeUnit.SECONDS);
                socket.connect(); timeout.cancel(false);
                synchronized (lock) { if (pending == socket) pending = null; }
                final BluetoothSocket connected = socket;
                main.post(() -> { if (run == epoch.get()) listener.onConnection(Peer.BLUETOOTH, wrap(connected), false); else close(connected); });
            } catch (IOException | RuntimeException e) {
                if (timeout != null) timeout.cancel(false); close(socket);
                synchronized (lock) { if (pending == socket) pending = null; }
                main.post(() -> { if (run == epoch.get()) listener.onConnectFailed(Peer.BLUETOOTH, "蓝牙连接失败：确认对方安装我在并开启接收；配对提示需要双方确认"); });
            }
        });
    }
    private StreamConnection wrap(BluetoothSocket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "蓝牙"; }
            public void close() throws IOException { socket.close(); }
        };
    }
    public void stop() {
        epoch.incrementAndGet();
        if (adapter != null) try { adapter.cancelDiscovery(); } catch (SecurityException ignored) {}
        if (registered) { try { context.unregisterReceiver(receiver); } catch (IllegalArgumentException ignored) {} registered = false; }
        synchronized (lock) { close(server); server = null; close(pending); pending = null; }
    }
    public void destroy() { stop(); worker.shutdownNow(); timer.shutdownNow(); }
    private static void close(Closeable object) { if (object != null) try { object.close(); } catch (IOException ignored) {} }
}
