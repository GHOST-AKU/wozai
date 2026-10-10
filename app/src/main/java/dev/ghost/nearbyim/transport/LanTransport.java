package dev.ghost.nearbyim.transport;

import android.content.Context;
import android.net.*;
import android.net.nsd.*;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import dev.ghost.nearbyim.i18n.UiText;
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
            } catch (IOException | RuntimeException e) { main.post(() -> { if (epoch.get() == run) { stop(); listener.onError(Peer.LAN, UiText.of("listenFailed"), true); } }); }
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
                public void onRegistrationFailed(NsdServiceInfo service, int code) { main.post(() -> { if (epoch.get() == run) listener.onError(Peer.LAN, UiText.of("lanRegisterFailure"), false); }); }
                public void onServiceUnregistered(NsdServiceInfo service) {}
                public void onUnregistrationFailed(NsdServiceInfo service, int code) {}
            };
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration);
        } catch (RuntimeException e) { listener.onError(Peer.LAN, UiText.of("discoveryFailed"), false); }
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
                if (epoch.get() == run && searchEpoch == scan) { discovery = null; listener.onError(Peer.LAN, UiText.of("discoveryFailed"), false); }
            }); }
            public void onStopDiscoveryFailed(String type, int code) { main.post(() -> {
                if (epoch.get() != run || searchEpoch != scan) return;
                listener.onSearching(Peer.LAN, !startedDiscovery.isEmpty());
                listener.onSearchStopFailed(Peer.LAN, UiText.of("searchStopFailure"));
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
            listener.onError(Peer.LAN, UiText.of("discoveryFailed"), false);
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
        connect(host,port,null);
    }
    public void connect(InetAddress host,int port,Network selectedNetwork) {
        if (host == null || !LocalEndpoint.isLocal(host) || port < 1 || port > 65535) { listener.onConnectFailed(Peer.LAN, UiText.of("invalidEndpoint")); return; }
        final int run = epoch.get(), attempt = connectEpoch.incrementAndGet();
        worker.execute(() -> {
            Socket socket = new Socket(); synchronized (lock) { if (run != epoch.get() || attempt != connectEpoch.get()) { close(socket); return; } pending.add(socket); }
            BoundConnection bound=null;
            try {
                ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network network=selectedNetwork==null?selectNetwork(manager,host):selectedNetwork;
                if(network!=null&&(manager==null||networkScore(manager.getNetworkCapabilities(network),manager.getLinkProperties(network),host)<0))throw new IOException("Selected network is not a local LAN route");
                bound=new BoundConnection(socket,network,manager);
                bindAndConnect(socket,new InetSocketAddress(host,port),network==null?null:network::bindSocket);
                socket.setTcpNoDelay(true); socket.setKeepAlive(true);BoundConnection connected=bound;
                synchronized (lock) { pending.remove(socket); }
                main.post(() -> { if (epoch.get() == run && connectEpoch.get() == attempt) listener.onConnection(Peer.LAN, connected, false); else close(connected); });
            } catch (IOException | RuntimeException e) {
                synchronized (lock) { pending.remove(socket); } if(bound!=null)close(bound);else close(socket);
                main.post(() -> { if (epoch.get() == run && connectEpoch.get() == attempt) listener.onConnectFailed(Peer.LAN, UiText.of("connectFailed")); });
            }
        });
    }
    interface SocketBinding {void bind(Socket socket)throws IOException;}
    static void bindAndConnect(Socket socket,InetSocketAddress endpoint,SocketBinding binding)throws IOException {
        try{if(binding!=null)binding.bind(socket);socket.connect(endpoint,8000);}
        catch(IOException|RuntimeException failure){close(socket);throw failure;}
    }
    static int networkScore(NetworkCapabilities capabilities,LinkProperties properties,InetAddress host) {
        if(capabilities==null||properties==null)return -1;List<IpPrefix> routes=new ArrayList<>();for(RouteInfo route:properties.getRoutes())routes.add(route.getDestination());
        return routeScore(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),routes,host);
    }
    static int routeScore(boolean wifi,boolean ethernet,boolean vpn,List<IpPrefix> routes,InetAddress host) {
        if(host==null||host.isLoopbackAddress()||vpn||!(wifi||ethernet))return -1;
        int prefix=-1;for(IpPrefix route:routes)if(route.contains(host))prefix=Math.max(prefix,route.getPrefixLength());return prefix;
    }
    private static Network selectNetwork(ConnectivityManager manager,InetAddress host) {
        if(manager==null||host.isLoopbackAddress())return null;Network selected=null;int best=-1;
        for(Network network:manager.getAllNetworks()){int score=networkScore(manager.getNetworkCapabilities(network),manager.getLinkProperties(network),host);if(score>best){selected=network;best=score;}}
        // Hotspot hosts may expose no Network object. Keep the kernel's local route as a manual fallback.
        return selected;
    }
    private static final class BoundConnection implements StreamConnection,Closeable {
        private final Socket socket;private final ConnectivityManager manager;private final Network network;
        private final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
        private final ConnectivityManager.NetworkCallback callback;
        BoundConnection(Socket socket,Network network,ConnectivityManager manager) {
            this.socket=socket;this.network=network;this.manager=manager;
            callback=network==null?null:new ConnectivityManager.NetworkCallback(){public void onLost(Network lost){if(BoundConnection.this.network.equals(lost))LanTransport.close(BoundConnection.this);}};
            if(callback!=null)manager.registerNetworkCallback(new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),callback);
        }
        public InputStream input()throws IOException{return socket.getInputStream();}
        public OutputStream output()throws IOException{return socket.getOutputStream();}
        public String label(){return "LAN · "+socket.getInetAddress().getHostAddress();}
        public void close()throws IOException {
            if(!closed.compareAndSet(false,true))return;
            try{socket.close();}finally{if(callback!=null)try{manager.unregisterNetworkCallback(callback);}catch(RuntimeException ignored){}}
        }
    }
    public static String endpoint(InetAddress host, int port) { String ip = host.getHostAddress(); return (ip.contains(":") ? "[" + ip + "]" : ip) + ":" + port; }
    private UiText addresses(int port) {
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
        return result.isEmpty() ? UiText.of("lanListeningWithoutAddress", Integer.toString(port)) : UiText.of("lanAddresses", String.join("\n", result));
    }
    private StreamConnection wrap(Socket socket) {
        return new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "LAN · " + socket.getInetAddress().getHostAddress(); }
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
