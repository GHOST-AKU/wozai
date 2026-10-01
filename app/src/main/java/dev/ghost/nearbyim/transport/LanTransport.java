package dev.ghost.nearbyim.transport;

import android.content.Context;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LanTransport {
    private static final String TYPE = "_nearbyim._tcp.";
    private final Context context;
    private final NsdManager nsd;
    private final TransportListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newCachedThreadPool();
    private final AtomicInteger epoch = new AtomicInteger(), connectEpoch = new AtomicInteger();
    private final Object lock = new Object();
    private final Set<Socket> pending = new HashSet<>();
    private ServerSocket server;
    private WifiManager.MulticastLock multicast;
    private NsdManager.RegistrationListener registration;
    private NsdManager.DiscoveryListener discovery;
    private final Set<NsdManager.DiscoveryListener> discoveryListeners = new HashSet<>(), startedDiscovery = new HashSet<>();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();
    private final Set<String> visible = new HashSet<>();
    private boolean resolving;
    private int searchEpoch;
    private String ownService;

    public LanTransport(Context context, TransportListener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        this.nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }
    public void start(String localId, String name) {
        stop(); final int run = epoch.get(); ownService = "nearby-" + localId.substring(0, 8);
        worker.execute(() -> {
            try {
                ServerSocket opened = new ServerSocket(0);
                synchronized (lock) { if (epoch.get() != run) { opened.close(); return; } server = opened; }
                main.post(() -> {
                    if (epoch.get() != run) return;
                    listener.onListening(Peer.LAN, addresses(opened.getLocalPort()));
                    register(run, localId, name, opened.getLocalPort()); discover();
                });
                while (epoch.get() == run) {
                    Socket socket = opened.accept();
                    if (!LocalEndpoint.isLocal(socket.getInetAddress())) { socket.close(); continue; }
                    socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                    main.post(() -> { if (epoch.get() == run) listener.onConnection(Peer.LAN, wrap(socket), true); else close(socket); });
                }
            } catch (IOException | RuntimeException e) { main.post(() -> { if (epoch.get() == run) { stop(); listener.onError(Peer.LAN, "无法开启局域网接收，请检查网络后重试", true); } }); }
        });
    }
    private void register(int run, String id, String name, int port) {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) { multicast = wifi.createMulticastLock("nearby-im-discovery"); multicast.setReferenceCounted(false); multicast.acquire(); }
            NsdServiceInfo info = new NsdServiceInfo(); info.setServiceName(ownService); info.setServiceType(TYPE); info.setPort(port);
            info.setAttribute("id", id); info.setAttribute("name", name);
            registration = new NsdManager.RegistrationListener() {
                public void onServiceRegistered(NsdServiceInfo service) { main.post(() -> { if (epoch.get() == run) ownService = service.getServiceName(); }); }
                public void onRegistrationFailed(NsdServiceInfo service, int code) { main.post(() -> { if (epoch.get() == run) listener.onError(Peer.LAN, "自动发现注册失败，可使用地址直连", false); }); }
                public void onServiceUnregistered(NsdServiceInfo service) {}
                public void onUnregistrationFailed(NsdServiceInfo service, int code) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) { listener.onError(Peer.LAN, "自动发现不可用，可使用地址直连", false); }
    }
    public void discover() {
        stopDiscovery(); resolveQueue.clear(); visible.clear(); final int run = epoch.get(), scan = ++searchEpoch;
        discovery = new NsdManager.DiscoveryListener() {
            public void onDiscoveryStarted(String type) { NsdManager.DiscoveryListener self = this; main.post(() -> {
                if (discoveryListeners.contains(self)) { startedDiscovery.add(self); listener.onSearching(Peer.LAN, true); }
            }); }
            public void onDiscoveryStopped(String type) { NsdManager.DiscoveryListener self = this; main.post(() -> {
                discoveryListeners.remove(self); startedDiscovery.remove(self); listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
            }); }
            public void onStartDiscoveryFailed(String type, int code) { NsdManager.DiscoveryListener self = this; main.post(() -> {
                discoveryListeners.remove(self); startedDiscovery.remove(self); listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
                if (epoch.get() == run && searchEpoch == scan) { discovery = null; listener.onError(Peer.LAN, "设备搜索失败，可使用地址直连", false); }
            }); }
            public void onStopDiscoveryFailed(String type, int code) { main.post(() -> {
                if (epoch.get() != run || searchEpoch != scan) return;
                listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
                listener.onSearchStopFailed(Peer.LAN, "停止设备搜索失败，请重试");
            }); }
            public void onServiceFound(NsdServiceInfo info) { main.post(() -> {
                if (epoch.get() != run || searchEpoch != scan || !info.getServiceType().startsWith("_nearbyim._tcp") || info.getServiceName().equals(ownService)) return;
                if (visible.add(info.getServiceName())) { resolveQueue.add(info); resolveNext(run, scan); }
            }); }
            public void onServiceLost(NsdServiceInfo info) { main.post(() -> {
                if (epoch.get() != run || searchEpoch != scan) return;
                visible.remove(info.getServiceName()); listener.onLost("lan:" + info.getServiceName());
            }); }
        };
        try { discoveryListeners.add(discovery); nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery); }
        catch (RuntimeException e) {
            discoveryListeners.remove(discovery); discovery = null; listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
            listener.onError(Peer.LAN, "设备搜索不可用，可使用地址直连", false);
        }
    }
    @SuppressWarnings("deprecation")
    private void resolveNext(int run, int scan) {
        if (resolving || resolveQueue.isEmpty() || run != epoch.get() || scan != searchEpoch) return;
        NsdServiceInfo info = resolveQueue.remove(); resolving = true;
        try { nsd.resolveService(info, new NsdManager.ResolveListener() {
            public void onResolveFailed(NsdServiceInfo service, int code) { main.post(() -> { resolving = false; resolveNext(epoch.get(), searchEpoch); }); }
            public void onServiceResolved(NsdServiceInfo service) { main.post(() -> {
                resolving = false;
                if (epoch.get() == run && searchEpoch == scan && visible.contains(service.getServiceName()) && service.getHost() != null && LocalEndpoint.isLocal(service.getHost())) {
                    byte[] rawName = service.getAttributes().get("name");
                    String name = rawName == null ? service.getServiceName() : new String(rawName, StandardCharsets.UTF_8);
                    byte[] rawId = service.getAttributes().get("id"); String peerId = null;
                    if (rawId != null) try { peerId = UUID.fromString(new String(rawId, StandardCharsets.UTF_8)).toString(); } catch (IllegalArgumentException ignored) {}
                    listener.onPeer(new Peer(Peer.LAN, "lan:" + service.getServiceName(), name,
                            endpoint(service.getHost(), service.getPort()), service.getHost(), service.getPort(), null, peerId));
                }
                resolveNext(epoch.get(), searchEpoch);
            }); }
        }); } catch (RuntimeException e) { resolving = false; resolveNext(run, scan); }
    }
    public void connect(InetAddress host, int port) {
        if (host == null || !LocalEndpoint.isLocal(host) || port < 1 || port > 65535) { listener.onConnectFailed(Peer.LAN, "仅支持有效的局域网地址和端口"); return; }
        final int run = epoch.get(), attempt = connectEpoch.incrementAndGet();
        worker.execute(() -> {
            Socket socket = new Socket(); synchronized (lock) { if (run != epoch.get() || attempt != connectEpoch.get()) { close(socket); return; } pending.add(socket); }
            try {
                socket.connect(new InetSocketAddress(host, port), 8000); socket.setTcpNoDelay(true); socket.setKeepAlive(true);
                synchronized (lock) { pending.remove(socket); }
                main.post(() -> { if (epoch.get() == run && connectEpoch.get() == attempt) listener.onConnection(Peer.LAN, wrap(socket), false); else close(socket); });
            } catch (IOException | RuntimeException e) {
                synchronized (lock) { pending.remove(socket); } close(socket);
                main.post(() -> { if (epoch.get() == run && connectEpoch.get() == attempt) listener.onConnectFailed(Peer.LAN, "连接失败：确认对方已开启接收，并检查路由器客户端隔离或 VPN"); });
            }
        });
    }
    public static String endpoint(InetAddress host, int port) { String ip = host.getHostAddress(); return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port; }
    private String addresses(int port) {
        List<String> result = new ArrayList<>();
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
                if (capabilities == null || !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue;
                LinkProperties properties = manager.getLinkProperties(network); if (properties == null) continue;
                for (LinkAddress address : properties.getLinkAddresses()) if (address.getAddress() instanceof Inet4Address && LocalEndpoint.isLocal(address.getAddress())) result.add(endpoint(address.getAddress(), port));
            }
            // Hotspot hosts can have a local interface absent from the active Network list.
            if (result.isEmpty()) for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp() || iface.isLoopback() || iface.getName().startsWith("tun")) continue;
                for (InetAddress address : Collections.list(iface.getInetAddresses())) if (address instanceof Inet4Address && LocalEndpoint.isLocal(address)) result.add(endpoint(address, port));
            }
        } catch (RuntimeException | SocketException ignored) {}
        return result.isEmpty() ? "监听端口 " + port + " · 请连接 Wi-Fi 或手机热点" : String.join("\n", result);
    }
    private StreamConnection wrap(Socket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "局域网 · " + socket.getInetAddress().getHostAddress(); }
            public void close() throws IOException { socket.close(); }
        };
    }
    private void stopDiscovery() {
        discovery = null;
        for (NsdManager.DiscoveryListener previous : new ArrayList<>(discoveryListeners)) {
            try { nsd.stopServiceDiscovery(previous); } catch (RuntimeException ignored) {}
        }
        listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
    }
    public void stopSearch() { searchEpoch++; stopDiscovery(); resolveQueue.clear(); visible.clear(); }
    public void cancelConnect() {
        connectEpoch.incrementAndGet();
        synchronized (lock) { for (Socket socket : pending) close(socket); pending.clear(); }
    }
    public void stop() {
        epoch.incrementAndGet(); cancelConnect(); searchEpoch++; stopDiscovery(); visible.clear(); resolveQueue.clear();
        if (registration != null) { try { nsd.unregisterService(registration); } catch (RuntimeException ignored) {} registration = null; }
        if (multicast != null) { if (multicast.isHeld()) multicast.release(); multicast = null; }
        synchronized (lock) { close(server); server = null; for (Socket socket : pending) close(socket); pending.clear(); }
    }
    public void destroy() { stop(); worker.shutdownNow(); }
    private static void close(Closeable object) { if (object != null) try { object.close(); } catch (IOException ignored) {} }
}
