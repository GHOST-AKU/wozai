package org.nearbymesh.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareNetworkInfo;
import android.net.wifi.aware.WifiAwareNetworkSpecifier;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only Wi-Fi Aware transport for the separate Rust mesh lab (API 29+).
 * Discovery labels and the shared lab PSK select links; only Rust authenticates peers.
 * Each peer has its own NDP request, network-bound TCP socket, and loopback bridge.
 * No ordinary Wi-Fi/LAN or process-default-network fallback is permitted.
 */
public final class AwareAdapter implements RadioAdapter {
    private static final String SERVICE = "nearbymesh-radio-lab-v1";
    private static final int MAX_PEERS = 4, REQUEST_MS = 20_000, ATTEMPT_MS = 40_000;
    private static final long DISCOVERY_TTL_MS = 60_000;
    private final Context context;
    private final Handler handler;
    private final RadioConfig config;
    private final RadioEvents events;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean started = new AtomicBoolean(), closed = new AtomicBoolean();
    // Four long-lived read/accept tasks leave four workers for bounded setup/cleanup work.
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(16), runnable -> {
                Thread thread = new Thread(runnable, "mesh-aware-io");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    // Only the supplied Handler mutates these maps and Aware/NetworkCallback state.
    private final Map<String, Candidate> candidates = new HashMap<>();
    private final Map<String, Discovered> discovered = new HashMap<>();
    private WifiAwareManager manager;
    private ConnectivityManager connectivity;
    private WifiAwareSession aware;
    private PublishDiscoverySession publisher;
    private SubscribeDiscoverySession subscriber;
    private boolean receiverRegistered;
    private int epoch, attachAttempts, nextMessageId;
    private Runnable startupDeadline, attachRetry;

    public AwareAdapter(Context context, Handler handler, RadioConfig config, RadioEvents events) {
        this.context = context.getApplicationContext();
        this.handler = handler;
        this.config = config;
        this.events = events;
    }

    @Override public void start() {
        if (!"aware".equals(config.mode)) throw new IllegalArgumentException("aware_mode_required");
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("already_started");
        if (closed.get()) throw new IllegalStateException("adapter_closed");
        handler.post(this::begin);
    }

    private void begin() {
        if (closed.get()) return;
        if (Build.VERSION.SDK_INT < 29
                || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            emit("unsupported", "", "wifi_aware_api29_required");
            return;
        }
        manager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || connectivity == null) {
            emit("unsupported", "", "wifi_aware_service_missing");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(stateReceiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(stateReceiver, filter, null, handler);
            }
            receiverRegistered = true;
            attach();
        } catch (RuntimeException error) {
            emit("failure", "", "aware_permission_or_registration_failed");
        }
    }

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (closed.get()) return;
            // The platform requires discarding all sessions on every availability change.
            teardown("aware_state_changed");
            attachAttempts = 0;
            attach();
        }
    };

    private void attach() {
        if (closed.get() || manager == null || aware != null) return;
        if (++attachAttempts > 3) {
            emit("failure", "", "aware_attach_retry_exhausted");
            return;
        }
        try {
            if (!manager.isAvailable()) {
                emit("unavailable", "", "wifi_aware_unavailable");
                return;
            }
            final int generation = ++epoch;
            startupDeadline = () -> startupFailed(generation, "aware_start_deadline");
            handler.postDelayed(startupDeadline, 15_000);
            manager.attach(new AttachCallback() {
                @Override public void onAttached(WifiAwareSession session) {
                    if (!current(generation)) { session.close(); return; }
                    aware = session;
                    try {
                        // A self-targeted HELLO with token zero is an announcement, never a link offer.
                        byte[] announcement = new AwareOffer(AwareOffer.HELLO,
                                config.label, config.label, 0).encode();
                        session.publish(new PublishConfig.Builder().setServiceName(SERVICE)
                                .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
                                .setServiceSpecificInfo(announcement)
                                .setTerminateNotificationEnabled(true).build(),
                                publishCallback(generation), handler);
                        session.subscribe(new SubscribeConfig.Builder().setServiceName(SERVICE)
                                .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
                                .setTerminateNotificationEnabled(true).build(),
                                subscribeCallback(generation), handler);
                    } catch (RuntimeException error) {
                        startupFailed(generation, "aware_discovery_start_failed");
                    }
                }
                @Override public void onAttachFailed() {
                    startupFailed(generation, "aware_attach_failed");
                }
                @Override public void onAwareSessionTerminated() {
                    startupFailed(generation, "aware_session_terminated");
                }
            }, handler);
        } catch (RuntimeException error) {
            startupFailed(epoch, "aware_permission_or_attach_failed");
        }
    }

    private boolean current(int generation) { return !closed.get() && epoch == generation; }

    private void startupFailed(int generation, String reason) {
        if (!current(generation)) return;
        emit("failure", "", reason);
        teardown(reason);
        attachRetry = () -> { attachRetry = null; attach(); };
        handler.postDelayed(attachRetry, 5_000);
    }

    private void discoveryStarted() {
        if (publisher != null && subscriber != null && startupDeadline != null) {
            handler.removeCallbacks(startupDeadline);
            startupDeadline = null;
            emit("discovery_started", "", "aware_publish_and_subscribe");
        }
    }

    private DiscoverySessionCallback publishCallback(final int generation) {
        return new DiscoverySessionCallback() {
            @Override public void onPublishStarted(PublishDiscoverySession session) {
                if (!current(generation)) { session.close(); return; }
                publisher = session;
                discoveryStarted();
            }
            @Override public void onMessageReceived(PeerHandle peer, byte[] bytes) {
                if (!current(generation) || publisher == null) return;
                AwareOffer offer = AwareOffer.parse(bytes);
                if (offer == null || offer.kind != AwareOffer.HELLO || offer.token == 0
                        || !config.label.equals(offer.target) || !config.accepts(offer.label)
                        || config.label.compareTo(offer.label) >= 0) return;
                Candidate existing = candidates.get(offer.label);
                if (existing != null) {
                    if (existing.server && existing.token == offer.token && existing.peer.equals(peer)
                            && existing.callbackRegistered && !existing.established) signal(existing);
                    return;
                }
                if (candidates.size() >= MAX_PEERS) return;
                Candidate candidate = new Candidate(offer.label, peer, offer.token, true);
                add(candidate);
                work(candidate, () -> prepareServer(candidate));
            }
            @Override public void onSessionConfigFailed() {
                startupFailed(generation, "aware_publish_failed");
            }
            @Override public void onSessionTerminated() {
                startupFailed(generation, "aware_publish_terminated");
            }
            @Override public void onMessageSendFailed(int messageId) {
                if (current(generation)) messageFailed(messageId);
            }
        };
    }

    private DiscoverySessionCallback subscribeCallback(final int generation) {
        return new DiscoverySessionCallback() {
            @Override public void onSubscribeStarted(SubscribeDiscoverySession session) {
                if (!current(generation)) { session.close(); return; }
                subscriber = session;
                discoveryStarted();
            }
            @Override public void onServiceDiscovered(PeerHandle peer, byte[] bytes, List<byte[]> filter) {
                if (!current(generation) || subscriber == null) return;
                AwareOffer offer = AwareOffer.parse(bytes);
                if (offer == null || offer.kind != AwareOffer.HELLO || offer.token != 0
                        || !offer.label.equals(offer.target) || !config.accepts(offer.label)
                        || config.label.compareTo(offer.label) <= 0) return;
                Discovered entry = discovered.get(offer.label);
                if (entry == null) {
                    if (discovered.size() >= MAX_PEERS) return;
                    entry = new Discovered(offer.label, peer);
                    discovered.put(offer.label, entry);
                    emit("peer_discovered", offer.label, "lab_label_untrusted");
                } else if (!entry.peer.equals(peer)) {
                    entry.peer = peer;
                    entry.failures = 0;
                }
                entry.lastSeen = SystemClock.elapsedRealtime();
                startClient(entry);
            }
            @Override public void onMessageReceived(PeerHandle peer, byte[] bytes) {
                if (!current(generation)) return;
                AwareOffer offer = AwareOffer.parse(bytes);
                if (offer == null || offer.kind != AwareOffer.READY
                        || !config.label.equals(offer.target)) return;
                Candidate candidate = candidates.get(offer.label);
                if (candidate == null || candidate.server || candidate.token != offer.token
                        || !candidate.peer.equals(peer) || candidate.callbackRegistered) return;
                request(candidate);
            }
            @Override public void onServiceLost(PeerHandle peer, int reason) {
                if (!current(generation)) return;
                for (Discovered entry : new ArrayList<>(discovered.values())) {
                    if (!entry.peer.equals(peer)) continue;
                    discovered.remove(entry.label);
                    if (entry.retry != null) handler.removeCallbacks(entry.retry);
                    Candidate candidate = candidates.get(entry.label);
                    if (candidate != null && !candidate.established) fail(candidate, "aware_discovery_lost");
                }
            }
            @Override public void onSessionConfigFailed() {
                startupFailed(generation, "aware_subscribe_failed");
            }
            @Override public void onSessionTerminated() {
                startupFailed(generation, "aware_subscribe_terminated");
            }
            @Override public void onMessageSendFailed(int messageId) {
                if (current(generation)) messageFailed(messageId);
            }
        };
    }

    private void startClient(Discovered entry) {
        if (closed.get() || discovered.get(entry.label) != entry || subscriber == null
                || candidates.containsKey(entry.label)
                || candidates.size() >= MAX_PEERS || entry.failures >= 3
                || SystemClock.elapsedRealtime() - entry.lastSeen > DISCOVERY_TTL_MS) return;
        if (entry.retry != null) { handler.removeCallbacks(entry.retry); entry.retry = null; }
        long token;
        do { token = random.nextLong(); } while (token == 0);
        Candidate candidate = new Candidate(entry.label, entry.peer, token, false);
        add(candidate);
        signal(candidate);
    }

    private void add(Candidate candidate) {
        candidates.put(candidate.label, candidate);
        candidate.deadline = () -> fail(candidate, "aware_link_deadline");
        handler.postDelayed(candidate.deadline, ATTEMPT_MS);
        candidate.signaling = new Runnable() {
            @Override public void run() {
                if (!live(candidate) || candidate.established) return;
                if (!candidate.server || candidate.callbackRegistered) signal(candidate);
                handler.postDelayed(this, 2_000);
            }
        };
        handler.postDelayed(candidate.signaling, 2_000);
    }

    private boolean live(Candidate candidate) {
        return !closed.get() && !candidate.done && candidates.get(candidate.label) == candidate;
    }

    private void signal(Candidate candidate) {
        if (!live(candidate) || candidate.established) return;
        try {
            AwareOffer offer = new AwareOffer(candidate.server ? AwareOffer.READY : AwareOffer.HELLO,
                    config.label, candidate.label, candidate.token);
            candidate.messageId = ++nextMessageId;
            if (candidate.server && publisher != null) {
                publisher.sendMessage(candidate.peer, candidate.messageId, offer.encode());
            } else if (!candidate.server && subscriber != null) {
                subscriber.sendMessage(candidate.peer, candidate.messageId, offer.encode());
            }
        } catch (RuntimeException error) {
            fail(candidate, "aware_signal_failed");
        }
    }

    private void messageFailed(int messageId) {
        for (Candidate candidate : candidates.values()) {
            if (candidate.messageId == messageId && !candidate.established) {
                emit("failure", candidate.label, "aware_discovery_message_failed_retrying");
                return;
            }
        }
    }

    private void prepareServer(Candidate candidate) {
        NativeSocket listener = null;
        try {
            listener = NativeSocket.create();
            Os.bind(listener.fd, Inet6Address.getByAddress(null, new byte[16], 0), 0);
            int port = ((InetSocketAddress) Os.getsockname(listener.fd)).getPort();
            if (!candidate.installListener(listener)) return;
            handler.post(() -> {
                if (!live(candidate)) return;
                candidate.port = port;
                request(candidate);
                if (live(candidate) && candidate.callbackRegistered) signal(candidate);
            });
        } catch (Exception error) {
            if (listener != null) listener.close();
            handler.post(() -> fail(candidate, "aware_listener_prepare_failed"));
        }
    }

    private void request(final Candidate candidate) {
        if (!live(candidate) || candidate.callbackRegistered) return;
        try {
            WifiAwareNetworkSpecifier.Builder builder = new WifiAwareNetworkSpecifier.Builder(
                    candidate.server ? publisher : subscriber, candidate.peer)
                    .setPskPassphrase(config.passphrase);
            if (candidate.server) builder.setPort(candidate.port).setTransportProtocol(OsConstants.IPPROTO_TCP);
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(builder.build()).build();
            candidate.callback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (!live(candidate)) return;
                    if (candidate.network != null && !network.equals(candidate.network)) {
                        fail(candidate, "aware_ndp_network_changed");
                        return;
                    }
                    candidate.network = network;
                    emit("network_available", candidate.label, "aware_ndp");
                    maybeOpen(candidate);
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (!live(candidate) || !network.equals(candidate.network)) return;
                    if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)) {
                        fail(candidate, "aware_wrong_network_transport");
                        return;
                    }
                    if (capabilities.getTransportInfo() instanceof WifiAwareNetworkInfo) {
                        WifiAwareNetworkInfo info = (WifiAwareNetworkInfo) capabilities.getTransportInfo();
                        candidate.peerAddress = info.getPeerIpv6Addr();
                        if (!candidate.server) {
                            candidate.port = info.getPort();
                            candidate.protocol = info.getTransportProtocol();
                        }
                        maybeOpen(candidate);
                    }
                }
                @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    if (!live(candidate) || !network.equals(candidate.network)) return;
                    LinkProperties snapshot = new LinkProperties();
                    snapshot.setInterfaceName(properties.getInterfaceName());
                    snapshot.setLinkAddresses(new ArrayList<>(properties.getLinkAddresses()));
                    candidate.properties = snapshot;
                    maybeOpen(candidate);
                }
                @Override public void onUnavailable() { fail(candidate, "aware_ndp_unavailable"); }
                @Override public void onLost(Network network) {
                    if (network.equals(candidate.network)) fail(candidate, "aware_ndp_lost");
                }
            };
            candidate.callbackRegistered = true;
            connectivity.requestNetwork(request, candidate.callback, handler, REQUEST_MS);
        } catch (RuntimeException error) {
            fail(candidate, "aware_network_request_failed");
        }
    }

    private void maybeOpen(Candidate candidate) {
        if (!live(candidate) || candidate.opening || candidate.network == null
                || candidate.peerAddress == null || candidate.properties == null) return;
        if (!candidate.peerAddress.isLinkLocalAddress() || candidate.peerAddress.getScopeId() <= 0
                || candidate.properties.getInterfaceName() == null
                || (!candidate.server && (candidate.port < 1 || candidate.port > 65535
                || candidate.protocol != OsConstants.IPPROTO_TCP))) {
            fail(candidate, "aware_invalid_peer_metadata");
            return;
        }
        candidate.opening = true;
        work(candidate, () -> openAndRead(candidate));
    }

    private void openAndRead(Candidate candidate) {
        String failureReason = "aware_ndp_scope_invalid";
        try {
            int interfaceId = Os.if_nametoindex(candidate.properties.getInterfaceName());
            if (interfaceId <= 0 || candidate.peerAddress.getScopeId() != interfaceId) {
                throw new IOException("ndp_scope_mismatch");
            }
            failureReason = candidate.server ? "aware_accept_failed" : "aware_connect_failed";
            NativeSocket socket = candidate.server ? accept(candidate, interfaceId) : connect(candidate);
            if (!candidate.installSocket(socket)) return;
            // Verify both ends against this request's NDP before opening the Rust IPC stream.
            failureReason = "aware_socket_address_outside_ndp";
            if (!belongsToNdp(candidate, socket, interfaceId)) throw new IOException("ndp_address_mismatch");
            CoreBridge bridge = new CoreBridge(config.corePort, new CoreBridge.Sink() {
                @Override public void send(byte[] bytes) throws IOException {
                    socket.write(bytes);
                    candidate.tx.addAndGet(bytes.length);
                }
                @Override public void close() { socket.close(); }
            }, () -> handler.post(() -> fail(candidate, "core_or_radio_stream_closed")));
            if (!candidate.installBridge(bridge)) return;
            failureReason = "aware_core_loopback_failed";
            bridge.start();
            handler.post(() -> {
                if (!live(candidate)) return;
                candidate.established = true;
                handler.removeCallbacks(candidate.deadline);
                handler.removeCallbacks(candidate.signaling);
                emit("link_up", candidate.label, "aware_tcp_core_bridge_untrusted_until_rust_auth");
            });
            failureReason = "aware_socket_stream_closed";
            byte[] buffer = new byte[CoreBridge.MAX_CHUNK];
            while (!candidate.done && !closed.get()) {
                int count;
                try { count = Os.read(socket.fd, buffer, 0, buffer.length); }
                catch (ErrnoException error) {
                    if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue;
                    throw error;
                }
                if (count <= 0) break;
                candidate.rx.addAndGet(count);
                if (!bridge.receive(Arrays.copyOf(buffer, count))) break;
            }
        } catch (Exception error) {
            // Exception messages may contain addresses; emit only a stable metadata reason.
        } finally {
            candidate.closeResources();
            final String reason = failureReason;
            handler.post(() -> fail(candidate, reason));
        }
    }

    private NativeSocket accept(Candidate candidate, int interfaceId) throws Exception {
        NativeSocket listener = candidate.listener;
        if (listener == null || candidate.done) throw new IOException("listener_closed");
        // The reserved wildcard port was never listening before binding it to this NDP.
        candidate.network.bindSocket(listener.fd);
        Os.listen(listener.fd, 1);
        long deadline = SystemClock.elapsedRealtime() + 12_000;
        int rejected = 0;
        while (!candidate.done && SystemClock.elapsedRealtime() < deadline && rejected < 8) {
            NativeSocket accepted;
            try { accepted = new NativeSocket(Os.accept(listener.fd, null)); }
            catch (ErrnoException error) {
                if (error.errno == OsConstants.EAGAIN || error.errno == OsConstants.EINTR) continue;
                throw error;
            }
            try {
                accepted.configure();
                if (belongsToNdp(candidate, accepted, interfaceId)) {
                    listener.close();
                    return accepted;
                }
            } catch (Exception error) {
                accepted.close();
                throw error;
            }
            accepted.close();
            ++rejected;
        }
        throw new IOException("accept_deadline_or_rejected_peers");
    }

    private NativeSocket connect(Candidate candidate) throws Exception {
        Exception last = null;
        // Capability callbacks on the two devices need not arrive in the same order.
        for (int attempt = 0; attempt < 5 && !candidate.done; ++attempt) {
            NativeSocket socket = NativeSocket.create();
            if (!candidate.installSocket(socket)) throw new IOException("candidate_closed");
            try {
                candidate.network.bindSocket(socket.fd);
                Os.connect(socket.fd, candidate.peerAddress, candidate.port);
                return socket;
            } catch (Exception error) {
                socket.close();
                last = error;
                if (candidate.done) break;
                Thread.sleep(300);
            }
        }
        throw new IOException("aware_connect_failed", last);
    }

    private boolean belongsToNdp(Candidate candidate, NativeSocket socket, int interfaceId) throws Exception {
        InetAddress remote = ((InetSocketAddress) Os.getpeername(socket.fd)).getAddress();
        InetAddress local = ((InetSocketAddress) Os.getsockname(socket.fd)).getAddress();
        if (!(remote instanceof Inet6Address) || !(local instanceof Inet6Address)
                || !remote.isLinkLocalAddress() || !local.isLinkLocalAddress()
                || !Arrays.equals(remote.getAddress(), candidate.peerAddress.getAddress())) return false;
        int remoteScope = ((Inet6Address) remote).getScopeId();
        int localScope = ((Inet6Address) local).getScopeId();
        if ((remoteScope != 0 && remoteScope != interfaceId)
                || (localScope != 0 && localScope != interfaceId)) return false;
        for (LinkAddress address : candidate.properties.getLinkAddresses()) {
            if (address.getAddress().isLinkLocalAddress()
                    && Arrays.equals(address.getAddress().getAddress(), local.getAddress())) return true;
        }
        return false;
    }

    private void work(Candidate candidate, Runnable task) {
        try { workers.execute(task); }
        catch (RejectedExecutionException error) { fail(candidate, "aware_worker_capacity"); }
    }

    private void fail(Candidate candidate, String reason) {
        if (candidates.get(candidate.label) != candidate) return;
        candidates.remove(candidate.label);
        // Native shutdown wakes its I/O worker, which closes CoreBridge in its finally block.
        // CoreBridge.start holds its lock during a bounded loopback connect: never wait here.
        candidate.stopSockets();
        if (candidate.callbackRegistered) {
            candidate.callbackRegistered = false;
            try { connectivity.unregisterNetworkCallback(candidate.callback); }
            catch (RuntimeException ignored) { }
        }
        handler.removeCallbacks(candidate.deadline);
        handler.removeCallbacks(candidate.signaling);
        events.emit(candidate.established ? "link_down" : "failure", candidate.label, reason,
                candidate.tx.get(), candidate.rx.get());
        Discovered entry = discovered.get(candidate.label);
        if (!candidate.server && !closed.get() && entry != null && ++entry.failures < 3) {
            entry.retry = () -> { entry.retry = null; startClient(entry); };
            handler.postDelayed(entry.retry, 3_000L * entry.failures);
        }
    }

    private void teardown(String reason) {
        ++epoch;
        if (startupDeadline != null) handler.removeCallbacks(startupDeadline);
        if (attachRetry != null) handler.removeCallbacks(attachRetry);
        startupDeadline = null;
        attachRetry = null;
        for (Discovered entry : discovered.values()) {
            if (entry.retry != null) handler.removeCallbacks(entry.retry);
        }
        discovered.clear();
        for (Candidate candidate : new ArrayList<>(candidates.values())) fail(candidate, reason);
        closeSession(publisher);
        closeSession(subscriber);
        closeSession(aware);
        publisher = null;
        subscriber = null;
        aware = null;
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        handler.post(() -> {
            teardown("adapter_closed");
            if (receiverRegistered) {
                try { context.unregisterReceiver(stateReceiver); }
                catch (IllegalArgumentException ignored) { }
                receiverRegistered = false;
            }
            workers.shutdownNow();
            emit("closed", "", "aware_resources_released");
        });
    }

    private void emit(String event, String peer, String reason) { events.emit(event, peer, reason, 0, 0); }

    private void closeSession(AutoCloseable session) {
        if (session == null) return;
        try { session.close(); }
        catch (Exception error) { emit("failure", "", "aware_session_close_failed"); }
    }

    private static final class Discovered {
        final String label;
        PeerHandle peer;
        long lastSeen;
        int failures;
        Runnable retry;
        Discovered(String label, PeerHandle peer) { this.label = label; this.peer = peer; }
    }

    private static final class Candidate {
        final String label;
        final PeerHandle peer;
        final long token;
        final boolean server;
        final AtomicLong tx = new AtomicLong(), rx = new AtomicLong();
        volatile boolean done;
        volatile NativeSocket listener, socket;
        volatile CoreBridge bridge;
        volatile Network network;
        volatile Inet6Address peerAddress;
        volatile LinkProperties properties;
        int port, protocol, messageId;
        boolean callbackRegistered, opening, established;
        ConnectivityManager.NetworkCallback callback;
        Runnable deadline, signaling;
        Candidate(String label, PeerHandle peer, long token, boolean server) {
            this.label = label; this.peer = peer; this.token = token; this.server = server;
        }
        synchronized boolean installListener(NativeSocket value) {
            if (done) { value.close(); return false; }
            listener = value;
            return true;
        }
        synchronized boolean installSocket(NativeSocket value) {
            if (done) { value.close(); return false; }
            socket = value;
            return true;
        }
        synchronized boolean installBridge(CoreBridge value) {
            if (done) { value.close(); return false; }
            bridge = value;
            return true;
        }
        synchronized void stopSockets() {
            done = true;
            if (listener != null) listener.close();
            if (socket != null) socket.close();
        }
        void closeResources() {
            stopSockets();
            if (bridge != null) bridge.close();
        }
    }

    /** Public Android OS APIs permit binding the listening descriptor to its supplied Network. */
    private static final class NativeSocket {
        final FileDescriptor fd;
        private final AtomicBoolean closed = new AtomicBoolean();
        NativeSocket(FileDescriptor fd) { this.fd = fd; }
        static NativeSocket create() throws Exception {
            NativeSocket value = new NativeSocket(Os.socket(OsConstants.AF_INET6, OsConstants.SOCK_STREAM, 0));
            try {
                Os.setsockoptInt(value.fd, OsConstants.IPPROTO_IPV6, OsConstants.IPV6_V6ONLY, 1);
                value.configure();
                return value;
            }
            catch (Exception error) { value.close(); throw error; }
        }
        void configure() throws ErrnoException {
            Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, OsConstants.TCP_NODELAY, 1);
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(1_000));
            // Linux also applies SO_SNDTIMEO to a blocking connect; no unbounded connect/write.
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO,
                    StructTimeval.fromMillis(5_000));
        }
        void write(byte[] bytes) throws IOException {
            if (bytes == null || bytes.length == 0 || bytes.length > CoreBridge.MAX_CHUNK) {
                throw new IOException("invalid_radio_chunk");
            }
            try {
                for (int offset = 0; offset < bytes.length;) {
                    if (closed.get()) throw new IOException("radio_socket_closed");
                    int count = Os.write(fd, bytes, offset, bytes.length - offset);
                    if (count <= 0) throw new IOException("radio_socket_write_failed");
                    offset += count;
                }
            } catch (ErrnoException error) { throw new IOException("radio_socket_write_failed", error); }
        }
        void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { Os.shutdown(fd, OsConstants.SHUT_RDWR); } catch (ErrnoException ignored) { }
            try { Os.close(fd); } catch (ErrnoException ignored) { }
        }
    }
}
