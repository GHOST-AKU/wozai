package dev.ghost.nearbyim.transport;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.*;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.*;
import android.net.wifi.p2p.nsd.*;
import android.os.*;
import dev.ghost.nearbyim.core.StreamConnection;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Public Android Wi-Fi P2P + interface-bound TCP. Neither discovery nor a command ACK proves a path. */
@SuppressLint("MissingPermission") // Checked at entry; every native command also handles permission revocation.
public final class WifiDirectTransport {
    private static final long CONNECT_MS = 40_000, DISCOVERY_MS = 30_000, INTERFACE_MS = 8_000;
    private final Context context;
    private final WifiP2pManager manager;
    private final WifiManager wifi;
    private final ConnectivityManager connectivity;
    private final boolean supported;
    private final TransportListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WifiDirectPolicy.Lifecycle lifecycle = new WifiDirectPolicy.Lifecycle();
    private final WifiDirectPolicy.CleanupGate cleanup = new WifiDirectPolicy.CleanupGate();
    private final WifiDirectSockets sockets = new WifiDirectSockets();
    private final ThreadPoolExecutor routes = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> { Thread thread = new Thread(runnable, "wozai-p2p-route"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    // Platform state below belongs to the main looper. Workers return immutable snapshots.
    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceInfo advertisement;
    private WifiP2pDnsSdServiceRequest request;
    private WifiDirectPolicy.Registry registry;
    private boolean receiverRegistered, searchRequested, searching, initialized, advertised, inspecting, inspectAgain, foreignReported, automaticDiscovery;
    private BroadcastReceiver receiver;
    private volatile boolean destroyed;
    private long run, operation, scan, inspection, groupGeneration, attempt;
    private Runnable afterCleanup, connectDeadline, startupWatchdog;
    private boolean queuedConnect;
    private CleanupWork cleanupWork;
    private Peer selected;
    private GroupIdentity ownedGroup;
    private String readyGroup;
    private String unprovenGroup;
    private long unprovenDeadline;
    private boolean unprovenRetry;
    private ConnectivityManager.NetworkCallback networkCallback;

    public WifiDirectTransport(Context context, TransportListener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        manager = (WifiP2pManager) this.context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifi = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        connectivity = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        supported = manager != null && this.context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT);
    }
    public boolean available() { return supported; }
    public boolean enabled() { try { return supported && wifi != null && wifi.isWifiEnabled(); } catch (RuntimeException denied) { return false; } }
    private boolean permitted() {
        return context.checkSelfPermission(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    public void start(String localId, String name) {
        onMain(() -> {
            if (destroyed) return;
            final long wanted = operation + 1; stopInternal(); if (wanted != operation) return;
            Runnable begin = () -> { if (!destroyed && wanted == operation) beginStart(localId, name); };
            if (cleanup.blocked()) {
                if (cleanupWork != null && cleanupWork.failed) error("directCleanupFailed", true);
                else { afterCleanup = begin; queuedConnect = false; }
            } else begin.run();
        });
    }
    private void beginStart(String localId, String name) {
        if (!available()) { error("directUnavailable", true); return; }
        if (!permitted()) { error("directPermissionRequired", true); return; }
        if (!enabled()) { error("directEnableFirst", true); return; }
        String id = WifiDirectPolicy.uuid(localId), displayName = localName(name);
        if (id == null) { error("directInvalidPeer", true); return; }
        run = lifecycle.start(); final long started = run; registry = new WifiDirectPolicy.Registry(id);
        WifiDirectPolicy.StartupDeadline startup = new WifiDirectPolicy.StartupDeadline(started, now() + 10_000);
        startupWatchdog = () -> {
            if (lifecycle.valid(started) && startup.expired(started, now())) {
                String failure = initialized ? "directServiceFailed" : "directChannelLost";
                stopInternal(); error(failure, true);
            }
        };
        main.postDelayed(startupWatchdog, 10_000);
        try {
            WifiP2pManager.Channel opened = manager.initialize(context, main.getLooper(), () -> onMain(() -> {
                if (lifecycle.valid(started)) { stopInternal(); error("directChannelLost", true); }
            }));
            if (!lifecycle.valid(started)) { if (opened != null) closeChannel(opened); return; }
            channel = opened;
            if (channel == null) { stopInternal(); error("directChannelLost", true); return; }
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            receiver = receiverFor(started);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            receiverRegistered = true;
            manager.setDnsSdResponseListeners(channel, (instance, type, device) -> onMain(() -> {
                if (lifecycle.valid(started) && searchRequested && device != null) report(registry.service(instance, type, device.deviceAddress, now()));
            }), (domain, values, device) -> onMain(() -> {
                if (lifecycle.valid(started) && searchRequested && device != null) report(registry.txt(domain, values, device.deviceAddress, now()));
            }));
            // A group already present at startup has no app provenance, even if its peer advertises our type.
            manager.requestGroupInfo(channel, group -> onMain(() -> {
                if (!lifecycle.valid(started)) return;
                if (group != null) { stopInternal(); error("directForeignGroup", true); return; }
                initialized = true;
                Map<String,String> txt = new HashMap<>(); txt.put("id", id); txt.put("name", displayName); txt.put("v", "1");
                advertisement = WifiP2pDnsSdServiceInfo.newInstance("wozai-" + id.substring(0, 8), WifiDirectPolicy.TYPE, txt);
                try {
                    manager.addLocalService(channel, advertisement, action(started, () -> {
                        advertised = true; startup.acknowledge(started); clearStartupWatchdog();
                        listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising"));
                        if (lifecycle.valid(started)) { automaticDiscovery = true; discoverInternal(); expirePeers(started); }
                    }, code -> { stopInternal(); error(reason(code, "directServiceFailed"), true); }));
                } catch (RuntimeException failure) { failStart(failure); }
            }));
        } catch (RuntimeException failure) { failStart(failure); }
    }
    private void failStart(RuntimeException failure) { stopInternal(); error(failure instanceof SecurityException ? "directPermissionRequired" : "directChannelLost", true); }
    private static String localName(String name) {
        if (name == null || name.trim().isEmpty()) return "wozai";
        String result = name.trim().replaceAll("\\p{Cntrl}", "");
        while (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 96) result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        return result.isEmpty() ? "wozai" : result;
    }
    private void report(Peer peer) {
        for (Peer lost : registry.takeLost()) listener.onLost(lost.key);
        if (peer != null) { listener.onPeer(peer); if (ownedGroup == null) inspectGroup(); }
    }
    private void expirePeers(long started) {
        if (!lifecycle.valid(started)) return;
        for (Peer peer : registry.expire(now())) listener.onLost(peer.key);
        main.postDelayed(() -> expirePeers(started), 5_000);
    }

    public void discover() { onMain(() -> { automaticDiscovery = true; discoverInternal(); }); }
    private void discoverInternal() {
        if (!lifecycle.valid(run) || !initialized || !advertised || cleanup.blocked()) { error("directBusy", false); return; }
        if (!permitted()) { error("directPermissionRequired", false); return; }
        // Starting a new scan waits for the previous global stop to be acknowledged.
        stopSearchInternal(true);
    }
    private void beginDiscovery(long started, long search) {
        if (!lifecycle.valid(started) || search != scan || cleanup.blocked()) return;
        request = WifiP2pDnsSdServiceRequest.newInstance(WifiDirectPolicy.TYPE); WifiP2pDnsSdServiceRequest added = request;
        WifiP2pManager.Channel discoveredOn = channel;
        searchRequested = true;
        try {
            manager.addServiceRequest(channel, added, new WifiP2pManager.ActionListener() {
                public void onSuccess() { onMain(() -> {
                    if (!lifecycle.valid(started) || search != scan || request != added) { removeRequest(discoveredOn, added); return; }
                    try { manager.discoverServices(channel, action(started, () -> {}, code -> {
                        if (search == scan) { stopSearchInternal(false); error(reason(code, "directDiscoveryFailed"), false); }
                    })); } catch (RuntimeException failure) { if (search == scan) { stopSearchInternal(false); error(permissionReason(failure, "directDiscoveryFailed"), false); } }
                }); }
                public void onFailure(int code) { onMain(() -> {
                    if (!lifecycle.valid(started) || search != scan) return;
                    request = null; searchRequested = false; searching = false; listener.onSearching(Peer.WIFI_DIRECT, false); error(reason(code, "directDiscoveryFailed"), false);
                }); }
            });
            main.postDelayed(() -> { if (lifecycle.valid(started) && search == scan) stopSearchInternal(false); }, DISCOVERY_MS);
        } catch (RuntimeException failure) { request = null; searchRequested = false; error(permissionReason(failure, "directDiscoveryFailed"), false); }
    }
    public void stopSearch() { onMain(() -> { automaticDiscovery = false; stopSearchInternal(false); }); }
    private void stopSearchInternal(boolean restart) {
        boolean hadSearch = request != null || searchRequested || searching;
        long search = ++scan, started = run; searchRequested = false;
        WifiP2pDnsSdServiceRequest previous = request; request = null; removeRequest(channel, previous);
        if (channel == null || !lifecycle.valid(started)) { searching = false; listener.onSearching(Peer.WIFI_DIRECT, false); return; }
        if (!hadSearch) { listener.onSearching(Peer.WIFI_DIRECT, false); if (restart) beginDiscovery(started, search); else scheduleIdleDiscovery(started, search); return; }
        try { manager.stopPeerDiscovery(channel, action(started, () -> {
            if (search != scan) return;
            searching = false; listener.onSearching(Peer.WIFI_DIRECT, false);
            if (restart) beginDiscovery(started, search); else scheduleIdleDiscovery(started, search);
        }, code -> {
            if (search != scan) return;
            listener.onSearchStopFailed(Peer.WIFI_DIRECT, UiText.of(reason(code, "directSearchStopFailed")));
        })); } catch (RuntimeException failure) {
            listener.onSearchStopFailed(Peer.WIFI_DIRECT, UiText.of(permissionReason(failure, "directSearchStopFailed")));
        }
    }
    private void scheduleIdleDiscovery(long started, long search) {
        if (!automaticDiscovery || !lifecycle.valid(started)) return;
        main.postDelayed(() -> {
            if (automaticDiscovery && lifecycle.valid(started) && search == scan && !cleanup.blocked() && !lifecycle.pending() && !lifecycle.ownsGroup() && !sockets.hasDelivered()) discoverInternal();
        }, 15_000);
    }

    public void connect(Peer peer) { onMain(() -> connectInternal(peer)); }
    private void connectInternal(Peer peer) {
        if (!lifecycle.valid(run) || !initialized || !advertised) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directBusy")); return; }
        if (!permitted()) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directPermissionRequired")); return; }
        if (registry == null || !registry.contains(peer, now())) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directInvalidPeer")); return; }
        if (lifecycle.foreign() || unprovenGroup != null) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directForeignGroup")); return; }
        if (sockets.hasDelivered() || lifecycle.pending()) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directBusy")); return; }
        if (cleanup.blocked()) {
            if (cleanupWork != null && cleanupWork.failed) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directCleanupFailed")); return; }
            long wanted = operation, started = run;
            queuedConnect = true; afterCleanup = () -> { if (wanted == operation && lifecycle.valid(started)) connectInternal(peer); }; return;
        }
        if (lifecycle.ownsGroup()) {
            disconnectInternal(); long wanted = operation, started = run;
            queuedConnect = true; afterCleanup = () -> { if (wanted == operation && lifecycle.valid(started)) connectInternal(peer); };
            if (!cleanup.blocked()) { Runnable next = afterCleanup; afterCleanup = null; queuedConnect = false; next.run(); } return;
        }
        selected = peer; final long started = run, ticket = attempt = lifecycle.beginAttempt();
        invalidateInspection(); closeGroupStreams();
        connectDeadline = () -> { if (lifecycle.validAttempt(started, ticket) && lifecycle.pending()) failAttempt("directConnectTimeout"); };
        main.postDelayed(connectDeadline, CONNECT_MS);
        WifiP2pConfig config = new WifiP2pConfig(); config.deviceAddress = peer.wifiDirectAddress; config.wps.setup = WpsInfo.PBC;
        try { manager.connect(channel, config, action(started, () -> {
            if (lifecycle.validAttempt(started, ticket) && lifecycle.pending()) inspectGroup();
        }, code -> { if (lifecycle.validAttempt(started, ticket) && lifecycle.pending()) rejectAttempt(reason(code, "directConnectFailed")); })); }
        catch (RuntimeException failure) { rejectAttempt(permissionReason(failure, "directConnectFailed")); }
    }
    public void cancelConnect() { onMain(() -> { if (queuedConnect) { afterCleanup = null; queuedConnect = false; } cancelInternal(); }); }
    private void cancelInternal() {
        if (!lifecycle.pending()) return;
        Peer previous = selected; GroupIdentity owned = ownedGroup; lifecycle.cancelAttempt(); selected = null;
        clearDeadline(); invalidateInspection(); closeGroupStreams(); lifecycle.loseGroup(run); ownedGroup = null;
        beginCleanup(channel, owned == null, owned, previous, false);
    }
    private void failAttempt(String key) {
        if (!lifecycle.pending()) return;
        cancelInternal(); listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of(key));
    }
    private void rejectAttempt(String key) {
        if (!lifecycle.pending()) return;
        // An explicit native command rejection has no accepted negotiation to cancel.
        GroupIdentity owned = ownedGroup; lifecycle.cancelAttempt(); selected = null;
        clearDeadline(); invalidateInspection(); closeGroupStreams(); lifecycle.loseGroup(run); ownedGroup = null;
        if (owned != null) beginCleanup(channel, false, owned, null, false); else scheduleIdleDiscovery(run, scan);
        listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of(key));
    }
    public void disconnect() { onMain(this::disconnectInternal); }
    private void disconnectInternal() {
        if (queuedConnect) { afterCleanup = null; queuedConnect = false; }
        if (lifecycle.pending()) { cancelInternal(); return; }
        GroupIdentity owned = ownedGroup; closeGroupStreams(); invalidateInspection(); selected = null;
        lifecycle.loseGroup(run); ownedGroup = null;
        if (owned != null) beginCleanup(channel, false, owned, null, false);
        else if (lifecycle.valid(run)) { listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising")); scheduleIdleDiscovery(run, scan); }
    }

    @SuppressWarnings("deprecation")
    private BroadcastReceiver receiverFor(long started) { return new BroadcastReceiver() {
        public void onReceive(Context ignored, Intent intent) {
            if (!lifecycle.valid(started)) return;
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED) != WifiP2pManager.WIFI_P2P_STATE_ENABLED) { stopInternal(); error("directEnableFirst", true); }
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                boolean active = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                if (!active) searching = false; else if (searchRequested) searching = true;
                listener.onSearching(Peer.WIFI_DIRECT, searching);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                NetworkInfo info = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (info != null && !info.isConnected()) groupLost();
                inspectGroup();
            }
        }
    }; }
    private void invalidateInspection() { inspection++; inspecting = inspectAgain = false; groupGeneration++; readyGroup = null; }
    private void inspectGroup() {
        if (!lifecycle.valid(run) || !initialized || cleanup.blocked()) return;
        if (inspecting) { inspectAgain = true; return; }
        inspecting = true; final long started = run, query = ++inspection;
        GroupSnapshot snapshot = new GroupSnapshot();
        try {
            manager.requestConnectionInfo(channel, info -> onMain(() -> {
                if (!lifecycle.valid(started) || query != inspection || cleanup.blocked()) return;
                snapshot.info = info; snapshot.haveInfo = true; completeInspection(started, query, snapshot);
            }));
            manager.requestGroupInfo(channel, group -> onMain(() -> {
                if (!lifecycle.valid(started) || query != inspection || cleanup.blocked()) return;
                snapshot.group = group; snapshot.haveGroup = true; completeInspection(started, query, snapshot);
            }));
        } catch (RuntimeException failure) {
            inspecting = false;
            if (lifecycle.pending()) failAttempt(permissionReason(failure, "directConnectFailed")); else error(permissionReason(failure, "directInterfaceUnavailable"), false);
        }
    }
    private static final class GroupSnapshot { WifiP2pInfo info; WifiP2pGroup group; boolean haveInfo, haveGroup; }
    private void completeInspection(long started, long query, GroupSnapshot snapshot) {
        if (!snapshot.haveInfo || !snapshot.haveGroup) return;
        inspecting = false; boolean again = inspectAgain; inspectAgain = false;
        handleGroup(started, snapshot.info, snapshot.group);
        if (again && lifecycle.valid(started) && query == inspection) inspectGroup();
    }
    private void handleGroup(long started, WifiP2pInfo info, WifiP2pGroup group) {
        if (info == null || !info.groupFormed || group == null) {
            if (ownedGroup != null || readyGroup != null || foreignReported || unprovenGroup != null) groupLost();
            return;
        }
        if (info.isGroupOwner != group.isGroupOwner() || info.groupOwnerAddress == null) { routeFailure("directInterfaceUnavailable"); return; }
        String counterpart = verifiedCounterpart(group);
        if (counterpart == null) {
            closeGroupStreams();
            String key = group.getInterface() + "/" + group.getNetworkName() + "/" + group.isGroupOwner();
            if (!key.equals(unprovenGroup)) {
                unprovenGroup = key; unprovenDeadline = now() + INTERFACE_MS;
                // An idle advertised receiver may not have heard this peer recently. Seek bounded
                // app-service provenance; never infer ownership from the invitation or group name.
                if (automaticDiscovery && !searchRequested) discoverInternal();
            }
            if (now() < unprovenDeadline) {
                if (!unprovenRetry) {
                    unprovenRetry = true;
                    main.postDelayed(() -> { if (lifecycle.valid(started) && key.equals(unprovenGroup)) { unprovenRetry = false; inspectGroup(); } }, 250);
                }
                return;
            }
            lifecycle.foreignGroup(started);
            if (lifecycle.pending()) failAttempt("directForeignGroup");
            else if (!foreignReported) { foreignReported = true; error("directForeignGroup", false); }
            return; // Preserve unrelated or unproven groups. Advertising never confers ownership.
        }
        unprovenGroup = null; unprovenRetry = false;
        foreignReported = false;
        GroupIdentity identity = new GroupIdentity(group, counterpart);
        if (!lifecycle.claimGroup(started, true)) return;
        if (ownedGroup != null && !ownedGroup.matches(group)) closeGroupStreams();
        ownedGroup = identity;
        if (searchRequested || searching) stopSearchInternal(false);
        String key = identity.key() + "/" + info.groupOwnerAddress.getHostAddress();
        if (key.equals(readyGroup)) return;
        readyGroup = key; final long generation = ++groupGeneration, ticket = lifecycle.pending() ? attempt : 0;
        sockets.reset(); unregisterNetwork();
        readyInterface(started, generation, ticket, group.getInterface(), info.groupOwnerAddress, info.isGroupOwner, now() + INTERFACE_MS);
    }
    private String verifiedCounterpart(WifiP2pGroup group) {
        if (ownedGroup != null && ownedGroup.matches(group)) return ownedGroup.peer;
        Collection<WifiP2pDevice> devices = group.isGroupOwner() ? group.getClientList() : Collections.singletonList(group.getOwner());
        for (WifiP2pDevice device : devices) {
            if (device == null) continue;
            String verified = WifiDirectPolicy.counterpart(device.deviceAddress, selected, lifecycle.pending(), registry, now());
            if (verified != null) return verified;
        }
        return null;
    }
    private static final class GroupIdentity {
        final String iface, network, peer; final boolean go;
        GroupIdentity(WifiP2pGroup group, String peer) { iface = group.getInterface(); network = group.getNetworkName(); go = group.isGroupOwner(); this.peer = peer; }
        boolean matches(WifiP2pGroup group) {
            if (group == null || !Objects.equals(iface, group.getInterface()) || !Objects.equals(network, group.getNetworkName()) || go != group.isGroupOwner()) return false;
            Collection<WifiP2pDevice> devices = go ? group.getClientList() : Collections.singletonList(group.getOwner());
            for (WifiP2pDevice device : devices) if (device != null && peer.equals(WifiDirectPolicy.address(device.deviceAddress))) return true;
            return false;
        }
        String key() { return iface + "/" + network + "/" + peer + "/" + go; }
    }
    private void readyInterface(long started, long generation, long ticket, String iface, InetAddress owner, boolean go, long deadline) {
        if (!groupCurrent(started, generation, ticket)) return;
        try {
            routes.execute(() -> {
                RouteSnapshot snapshot = inspectRoute(iface, owner, go);
                onMain(() -> {
                    if (!groupCurrent(started, generation, ticket)) return;
                    if (snapshot.route != null) openSockets(started, generation, ticket, snapshot, owner, go);
                    else if (snapshot.failure != null) routeFailure(snapshot.failure);
                    else if (now() >= deadline) routeFailure("directInterfaceUnavailable");
                    else main.postDelayed(() -> readyInterface(started, generation, ticket, iface, owner, go, deadline), 250);
                });
            });
        } catch (RejectedExecutionException busy) { routeFailure("directBusy"); }
    }
    private boolean groupCurrent(long started, long generation, long ticket) {
        return lifecycle.valid(started) && generation == groupGeneration && !cleanup.blocked() && (ticket == 0 || lifecycle.validAttempt(started, ticket));
    }
    private static final class RouteSnapshot { final WifiDirectPolicy.Route route; final Network network; final String failure;
        RouteSnapshot(WifiDirectPolicy.Route route, Network network, String failure) { this.route = route; this.network = network; this.failure = failure; }
    }
    private RouteSnapshot inspectRoute(String iface, InetAddress owner, boolean go) {
        if (!(owner instanceof Inet4Address)) return new RouteSnapshot(null, null, "directIpv6Unsupported");
        try {
            List<WifiDirectPolicy.InterfaceRoute> interfaces = new ArrayList<>();
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) interfaces.add(new WifiDirectPolicy.InterfaceRoute(networkInterface.getName(), address.getAddress(), address.getNetworkPrefixLength()));
            }
            WifiDirectPolicy.Route proof = WifiDirectPolicy.prove(iface, owner, go, interfaces, true, false);
            if (proof == null) return new RouteSnapshot(null, null, null); // The actual interface is not assigned yet.
            Network matching = null; boolean vpn = false;
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.isPointToPoint()) vpn = true;
            }
            if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network); LinkProperties properties = connectivity.getLinkProperties(network);
                if (capabilities == null) continue;
                vpn |= capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || properties == null || !Objects.equals(iface, properties.getInterfaceName())) continue;
                boolean local = false, route = false;
                for (LinkAddress address : properties.getLinkAddresses()) if (proof.local.equals(address.getAddress()) && proof.prefix == address.getPrefixLength()) local = true;
                for (RouteInfo candidate : properties.getRoutes()) if (candidate.getDestination().getPrefixLength() > 0 && candidate.getDestination().contains(owner)) route = true;
                if (local && route) { if (matching != null) return new RouteSnapshot(null, null, "directRouteAmbiguous"); matching = network; }
            }
            // ServerSocket has no public Android Network binding. A matching Network alone
            // cannot disambiguate a GO return route; only the GC actually binds its socket.
            WifiDirectPolicy.Route accepted = WifiDirectPolicy.prove(iface, owner, go, interfaces, !go && matching != null, vpn);
            return new RouteSnapshot(accepted, matching, accepted == null ? "directRouteAmbiguous" : null);
        } catch (SecurityException denied) { return new RouteSnapshot(null, null, "directPermissionRequired"); }
        catch (SocketException | RuntimeException unavailable) { return new RouteSnapshot(null, null, null); }
    }
    private void openSockets(long started, long generation, long ticket, RouteSnapshot route, InetAddress owner, boolean go) {
        if (!groupCurrent(started, generation, ticket)) return;
        final long socketGeneration = sockets.reset();
        try { monitorNetwork(started, generation, route.network); } catch (RuntimeException denied) { routeFailure(permissionReason(denied, "directRouteAmbiguous")); return; }
        WifiDirectSockets.Events events = new WifiDirectSockets.Events() {
            public void onReady(int port) { onMain(() -> {
                if (groupCurrent(started, generation, ticket) && sockets.current(socketGeneration)) listener.onListening(Peer.WIFI_DIRECT, UiText.of("directListening", LanTransport.endpoint(route.route.local, port)));
            }); }
            public void onConnection(StreamConnection connection, boolean incoming) { onMain(() -> {
                if (!groupCurrent(started, generation, ticket) || !sockets.current(socketGeneration)) { close(connection); return; }
                if (ticket != 0) { lifecycle.connected(started, ticket); selected = null; clearDeadline(); }
                // incoming is TCP accept / Noise responder. It never expresses the user's request.
                listener.onConnection(Peer.WIFI_DIRECT, connection, incoming);
            }); }
            public void onFailure(Exception failure) { onMain(() -> {
                if (groupCurrent(started, generation, ticket) && sockets.current(socketGeneration)) routeFailure("directConnectFailed");
            }); }
        };
        if (go) sockets.listen(socketGeneration, route.route.local, WifiDirectPolicy.PORT, route.route::accepts, events);
        else sockets.connect(socketGeneration, route.route.local, owner, WifiDirectPolicy.PORT, route.network == null ? null : route.network::bindSocket, events);
    }
    private void monitorNetwork(long started, long generation, Network network) {
        unregisterNetwork(); if (network == null || connectivity == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            public void onLost(Network lost) { if (network.equals(lost)) onMain(() -> {
                if (lifecycle.valid(started) && generation == groupGeneration) { closeGroupStreams(); if (lifecycle.pending()) failAttempt("directGroupLost"); else error("directGroupLost", false); inspectGroup(); }
            }); }
        };
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
    }
    private void unregisterNetwork() {
        if (networkCallback != null && connectivity != null) try { connectivity.unregisterNetworkCallback(networkCallback); } catch (RuntimeException ignored) {}
        networkCallback = null;
    }
    private void routeFailure(String key) { closeGroupStreams(); if (lifecycle.pending()) failAttempt(key); else error(key, false); }
    private void groupLost() {
        boolean delivered = sockets.hasDelivered(), owned = lifecycle.ownsGroup();
        closeGroupStreams(); invalidateInspection(); lifecycle.loseGroup(run); ownedGroup = null;
        unprovenGroup = null; unprovenRetry = false; foreignReported = false;
        if (!lifecycle.pending()) selected = null;
        if (lifecycle.pending() && owned) rejectAttempt("directGroupLost");
        else if (delivered) error("directGroupLost", false);
        if (owned && lifecycle.valid(run)) listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising"));
        scheduleIdleDiscovery(run, scan);
    }
    private void closeGroupStreams() { groupGeneration++; readyGroup = null; sockets.reset(); unregisterNetwork(); }
    private void clearDeadline() { if (connectDeadline != null) main.removeCallbacks(connectDeadline); connectDeadline = null; }
    private void clearStartupWatchdog() { if (startupWatchdog != null) main.removeCallbacks(startupWatchdog); startupWatchdog = null; }

    public void stop() { onMain(this::stopInternal); }
    private void stopInternal() {
        operation++; afterCleanup = null; queuedConnect = false; automaticDiscovery = false; unprovenGroup = null; unprovenRetry = false; foreignReported = false;
        boolean pending = lifecycle.pending(); GroupIdentity owned = ownedGroup; Peer peer = selected;
        boolean hadSearch = request != null || searchRequested || searching;
        WifiP2pManager.Channel previous = channel; WifiP2pDnsSdServiceInfo service = advertisement; WifiP2pDnsSdServiceRequest oldRequest = request;
        lifecycle.stop(); run = 0; initialized = advertised = false; selected = null; ownedGroup = null; channel = null; advertisement = null; request = null; registry = null;
        clearDeadline(); clearStartupWatchdog(); invalidateInspection(); closeGroupStreams(); scan++; searchRequested = searching = false;
        if (receiverRegistered) { try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {} receiverRegistered = false; } receiver = null;
        if (previous != null) {
            removeRequest(previous, oldRequest);
            if (service != null) try { manager.removeLocalService(previous, service, null); } catch (RuntimeException ignored) {}
            if (hadSearch) try { manager.stopPeerDiscovery(previous, null); } catch (RuntimeException ignored) {}
            beginCleanup(previous, pending && owned == null, owned, peer, true);
        }
        listener.onSearching(Peer.WIFI_DIRECT, false);
    }
    /** Cleanup is global in Android. Keep a barrier until its ACK; timeout refuses later starts. */
    private void beginCleanup(WifiP2pManager.Channel old, boolean cancel, GroupIdentity owned, Peer peer, boolean closeChannel) {
        if (old == null) return;
        if (cleanup.blocked()) {
            if (cleanupWork != null && cleanupWork.channel == old) cleanupWork.closeChannel |= closeChannel;
            return;
        }
        if (!cancel && owned == null) { if (closeChannel) closeChannel(old); return; }
        final long token = cleanup.begin(); final long started = run;
        CleanupWork work = cleanupWork = new CleanupWork(token, old, closeChannel);
        Runnable complete = () -> {
            if (!cleanup.current(token)) return;
            if (work.closeChannel) closeChannel(old);
            if (!cleanup.complete(token)) return;
            cleanupWork = null;
            Runnable next = afterCleanup; afterCleanup = null; queuedConnect = false;
            if (next != null && !destroyed) next.run();
            else if (lifecycle.valid(started)) { listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising")); scheduleIdleDiscovery(started, scan); }
        };
        Runnable inspect = () -> {
            if (!cleanup.current(token)) return;
            try { manager.requestGroupInfo(old, group -> onMain(() -> {
                if (!cleanup.current(token)) return;
                boolean ours = owned != null ? owned.matches(group) : peer != null && matchesAttempt(group, peer.wifiDirectAddress);
                if (!ours) { complete.run(); return; }
                try { manager.removeGroup(old, new WifiP2pManager.ActionListener() {
                    public void onSuccess() { onMain(complete); }
                    public void onFailure(int code) { onMain(() -> { cleanupFailed(token); }); }
                }); } catch (RuntimeException failure) { cleanupFailed(token); }
            })); } catch (RuntimeException failure) { cleanupFailed(token); }
        };
        main.postDelayed(() -> cleanupFailed(token), 4_000);
        if (cancel) try { manager.cancelConnect(old, new WifiP2pManager.ActionListener() {
            public void onSuccess() { onMain(inspect); }
            public void onFailure(int code) { onMain(() -> cleanupFailed(token)); }
        }); } catch (RuntimeException failure) { cleanupFailed(token); }
        else inspect.run();
    }
    private void cleanupFailed(long token) {
        if (!cleanup.current(token) || cleanupWork == null || cleanupWork.failed) return;
        // Do not release the barrier on timeout/failure: a late native removal could hit a new group.
        cleanupWork.failed = true; afterCleanup = null; queuedConnect = false; error("directCleanupFailed", false);
    }
    private static final class CleanupWork {
        final long token; final WifiP2pManager.Channel channel; boolean closeChannel, failed;
        CleanupWork(long token, WifiP2pManager.Channel channel, boolean closeChannel) { this.token = token; this.channel = channel; this.closeChannel = closeChannel; }
    }
    private static boolean matchesAttempt(WifiP2pGroup group, String address) {
        if (group == null || address == null) return false;
        Collection<WifiP2pDevice> devices = group.isGroupOwner() ? group.getClientList() : Collections.singletonList(group.getOwner());
        for (WifiP2pDevice device : devices) if (device != null && address.equals(WifiDirectPolicy.address(device.deviceAddress))) return true;
        return false;
    }
    private void removeRequest(WifiP2pManager.Channel old, WifiP2pDnsSdServiceRequest previous) {
        if (old != null && previous != null) try { manager.removeServiceRequest(old, previous, null); } catch (RuntimeException ignored) {}
    }
    private static void closeChannel(WifiP2pManager.Channel channel) { if (Build.VERSION.SDK_INT >= 27) try { channel.close(); } catch (RuntimeException ignored) {} }
    public void destroy() { onMain(() -> { if (destroyed) return; stopInternal(); destroyed = true; if (cleanupWork != null) closeChannel(cleanupWork.channel); sockets.close(); routes.shutdownNow(); }); }
    private interface Failure { void failed(int code); }
    private WifiP2pManager.ActionListener action(long started, Runnable success, Failure failure) {
        return new WifiP2pManager.ActionListener() {
            public void onSuccess() { onMain(() -> { if (lifecycle.valid(started)) success.run(); }); }
            public void onFailure(int code) { onMain(() -> { if (lifecycle.valid(started)) failure.failed(code); }); }
        };
    }
    private static String reason(int code, String fallback) { return code == WifiP2pManager.P2P_UNSUPPORTED ? "directUnavailable" : code == WifiP2pManager.BUSY ? "directBusy" : fallback; }
    private static String permissionReason(RuntimeException failure, String fallback) { return failure instanceof SecurityException ? "directPermissionRequired" : fallback; }
    private void error(String key, boolean fatal) { listener.onError(Peer.WIFI_DIRECT, UiText.of(key), fatal); }
    private void onMain(Runnable runnable) { if (Looper.myLooper() == main.getLooper()) runnable.run(); else main.post(runnable); }
    private static long now() { return SystemClock.elapsedRealtime(); }
    private static void close(Closeable closeable) { try { if (closeable != null) closeable.close(); } catch (IOException ignored) {} }
}
