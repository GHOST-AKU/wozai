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
import dev.ghost.nearbyim.core.DirectGroupCredentials;
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
    private final WifiDirectPolicy.SessionAuthority authority = new WifiDirectPolicy.SessionAuthority();
    private final WifiDirectPolicy.FileAttempt fileAttempt = new WifiDirectPolicy.FileAttempt();
    private final WifiDirectSockets sockets = new WifiDirectSockets();
    private final ThreadPoolExecutor routes = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> { Thread thread = new Thread(runnable, "wozai-p2p-route"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    // Platform state below belongs to the main looper. Workers return immutable snapshots.
    private WifiP2pManager.Channel channel;
    private WifiP2pDnsSdServiceInfo advertisement;
    private final WifiDirectPolicy.RequestLedger<WifiP2pDnsSdServiceRequest> requests = new WifiDirectPolicy.RequestLedger<>();
    private WifiDirectPolicy.Registry registry;
    private boolean receiverRegistered, searchRequested, searching, initialized, advertised, inspecting, inspectAgain, foreignReported, automaticDiscovery;
    private BroadcastReceiver receiver;
    private volatile boolean destroyed;
    private volatile int observedP2pState;
    private volatile String lastStage="idle";
    private volatile int serviceResponses,txtResponses;
    private volatile String directMethod="dns_sd",coordinatedInterface;private volatile int groupFrequency;
    private volatile String bandPolicy="platform_default";
    private CoordinatedGroup coordinated;
    private static final class CoordinatedGroup {
        final boolean owner;final String peerId;final DirectGroupCredentials parameters;final WifiDirectPolicy.KnownGroup policy;
        final java.util.function.Consumer<DirectGroupCredentials> ready;
        final WifiDirectPolicy.GroupReadiness readiness=new WifiDirectPolicy.GroupReadiness(INTERFACE_MS);
        boolean observed;Runnable retry,timeout;
        CoordinatedGroup(String peerId,boolean owner,boolean prefer5,DirectGroupCredentials parameters,java.util.function.Consumer<DirectGroupCredentials> ready){
            this(peerId,owner,prefer5,true,parameters,ready);
        }
        CoordinatedGroup(String peerId,boolean owner,boolean prefer5,boolean allowAutoFallback,DirectGroupCredentials parameters,java.util.function.Consumer<DirectGroupCredentials> ready){
            this.peerId=peerId;this.owner=owner;this.parameters=parameters;this.ready=ready;policy=new WifiDirectPolicy.KnownGroup(parameters.ssid,owner,prefer5,allowAutoFallback);
        }
    }
    final WifiDirectDiagnostics diagnostics=new WifiDirectDiagnostics(SystemClock::elapsedRealtime);
    void beginFileAttemptDiagnostics(String nonce,boolean coordinator){diagnostics.begin(nonce,coordinator);serviceResponses=txtResponses=0;bandPolicy="platform_default";}
    void beginKnownGroupDiagnostics(String nonce,boolean owner){beginFileAttemptDiagnostics(nonce,owner);directMethod="known_group";groupFrequency=0;lastStage=owner?"preparing_group":"waiting_group_parameters";}
    public String discoveryDiagnostics(){return "wifi_direct_phase="+lastStage+"\nwifi_direct_method="+directMethod+"\nwifi_direct_band_policy="+bandPolicy+"\nwifi_direct_group_frequency_mhz="+groupFrequency+"\nwifi_direct_service_responses="+serviceResponses+"\nwifi_direct_txt_responses="+txtResponses+"\n"+diagnostics.snapshot();}
    private long run, operation, scan, inspection, groupGeneration, attempt;
    private Runnable afterCleanup, afterRequests, connectDeadline, startupWatchdog;
    private boolean queuedConnect;
    private boolean requestCleanupFailed;
    private CleanupWork cleanupWork;
    private Peer selected;
    private volatile GroupIdentity observedGroup;
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
    public boolean fiveGhzSupported(){try{return wifi!=null&&wifi.is5GHzBandSupported();}catch(RuntimeException unavailable){return false;}}
    public boolean advertising(){return initialized&&advertised&&lifecycle.valid(run);}
    public boolean enabled() { try { return supported && wifi != null && wifi.isWifiEnabled(); } catch (RuntimeException denied) { return false; } }
    /** Last native observation, kept separately from the station Wi-Fi switch. */
    public String p2pState() { return observedP2pState==WifiP2pManager.WIFI_P2P_STATE_ENABLED?"enabled":observedP2pState==WifiP2pManager.WIFI_P2P_STATE_DISABLED?"disabled":"unknown"; }
    /** Current-group RAM hint only. connect() rechecks the group/interface; Noise proves identity. */
    public Peer currentPeer(String peerId) { return authority.currentPeer(peerId); }
    public String currentInterface() { if(coordinatedInterface!=null)return coordinatedInterface;GroupIdentity group=observedGroup;return group==null?null:group.iface; }
    /** Called only after the approved chat has negotiated a new authenticated file attempt. */
    public void armFileAttempt(String peerId, boolean dialer) {
        onMain(() -> {
            if (destroyed) return;
            automaticDiscovery=true;fileAttempt.arm(peerId, dialer); authority.rearmFile();
            Peer retained = authority.currentPeer(peerId);
            if (retained != null && fileAttempt.dial(retained)) connectInternal(retained);
            if (initialized) { inspectGroup(); discoverInternal(); }
        });
    }
    private boolean permitted() {
        return context.checkSelfPermission(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.NEARBY_WIFI_DEVICES : Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    public void start(String localId, String name) {
        startSession(localId,name,null);
    }
    public void startGroup(String localId,String name,String remoteId,boolean owner,boolean prefer5,DirectGroupCredentials parameters,java.util.function.Consumer<DirectGroupCredentials> ready){
        startGroup(localId,name,remoteId,owner,prefer5,true,parameters,ready);
    }
    public void startGroup(String localId,String name,String remoteId,boolean owner,boolean prefer5,boolean allowAutoFallback,DirectGroupCredentials parameters,java.util.function.Consumer<DirectGroupCredentials> ready){
        if(Build.VERSION.SDK_INT<29){listener.onConnectFailed(Peer.WIFI_DIRECT,UiText.of("directUnavailable"));return;}
        startSession(localId,name,new CoordinatedGroup(remoteId,owner,prefer5,allowAutoFallback,parameters,ready));
    }
    private void startSession(String localId,String name,CoordinatedGroup group) {
        onMain(() -> {
            if (destroyed) return;
            final long wanted = operation + 1; stopInternal(); if (wanted != operation) return;
            Runnable begin = () -> { if (!destroyed && wanted == operation) beginStart(localId, name,group); };
            if (cleanup.blocked() || requests.occupied()) {
                if (cleanupWork != null && cleanupWork.failed || requests.occupied() && requestCleanupFailed) error("directCleanupFailed", true);
                else { afterCleanup = begin; queuedConnect = false; }
            } else begin.run();
        });
    }
    private void beginStart(String localId, String name,CoordinatedGroup group) {
        if (!available()) { error("directUnavailable", true); return; }
        if (!permitted()) { error("directPermissionRequired", true); return; }
        String id = WifiDirectPolicy.uuid(localId), displayName = localName(name);
        if (id == null) { error("directInvalidPeer", true); return; }
        coordinated=group;directMethod=group==null?"dns_sd":"known_group";groupFrequency=0;
        observedP2pState=0;lastStage="initializing";serviceResponses=txtResponses=0;
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
            diagnostics.record(WifiDirectDiagnostics.Event.CHANNEL_READY);
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
            receiver = receiverFor(started);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            else context.registerReceiver(receiver, filter);
            receiverRegistered = true;
            manager.setDnsSdResponseListeners(channel, (instance, type, device) -> onMain(() -> {
                if (lifecycle.valid(started) && searchRequested && device != null) {serviceResponses++;diagnostics.recordOnce(WifiDirectDiagnostics.Event.SERVICE_RESPONSE);report(registry.service(instance, type, device.deviceAddress, now()));}
            }), (domain, values, device) -> onMain(() -> {
                if (lifecycle.valid(started) && searchRequested && device != null) {txtResponses++;diagnostics.recordOnce(WifiDirectDiagnostics.Event.TXT_RESPONSE);report(registry.txt(domain, values, device.deviceAddress, now()));}
            }));
            // A hotspot host can have its station Wi-Fi switch off. Ask the P2P service,
            // rather than inferring P2P availability from WifiManager.isWifiEnabled().
            if (Build.VERSION.SDK_INT >= 29) manager.requestP2pState(channel, state -> onMain(() -> {
                if (!lifecycle.valid(started)) return;
                observedP2pState=state;
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) { stopInternal(); error("directEnableFirst", true); return; }
                advertise(started,id,displayName,startup);
            }));
            else advertise(started,id,displayName,startup);
        } catch (RuntimeException failure) { failStart(failure); }
    }
    private void advertise(long started,String id,String displayName,WifiDirectPolicy.StartupDeadline startup) {
        if (!lifecycle.valid(started)) return;
        if(coordinated!=null){beginCoordinated(started,startup);return;}
        // A group already present at startup has no app provenance, even if its peer advertises our type.
        try { manager.requestGroupInfo(channel, group -> onMain(() -> {
            if (!lifecycle.valid(started)) return;
            if (group != null) { stopInternal(); error("directForeignGroup", true); return; }
            initialized = true;
            Map<String,String> txt = new HashMap<>(); txt.put("id", id); txt.put("name", displayName); txt.put("v", "1");
            advertisement = WifiP2pDnsSdServiceInfo.newInstance("wozai-" + id.substring(0, 8), WifiDirectPolicy.TYPE, txt);
            try {
                manager.addLocalService(channel, advertisement, action(started, () -> {
                    lastStage="advertising";advertised = true; startup.acknowledge(started); clearStartupWatchdog();
                    diagnostics.record(WifiDirectDiagnostics.Event.ADVERTISEMENT_READY,0);
                    listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising"));
                    if (lifecycle.valid(started)) { automaticDiscovery = true; discoverInternal(); expirePeers(started); }
                }, code -> { diagnostics.record(WifiDirectDiagnostics.Event.ADVERTISEMENT_FAILED,code);stopInternal(); error(reason(code, "directServiceFailed"), true); }));
            } catch (RuntimeException failure) { failStart(failure); }
        })); } catch (RuntimeException failure) { failStart(failure); }
    }
    private void beginCoordinated(long started,WifiDirectPolicy.StartupDeadline startup){
        CoordinatedGroup group=coordinated;
        try{manager.requestGroupInfo(channel,current->onMain(()->{
            if(!lifecycle.valid(started)||coordinated!=group)return;
            if(current!=null){stopInternal();error("directForeignGroup",true);return;}
            initialized=true;fileAttempt.arm(group.peerId,false);authority.rearmFile();
            startup.acknowledge(started);clearStartupWatchdog();attempt=lifecycle.beginAttempt();long ticket=attempt;
            connectDeadline=()->{if(lifecycle.validAttempt(started,ticket)&&lifecycle.pending())failAttempt("directConnectTimeout");};
            main.postDelayed(connectDeadline,CONNECT_MS);issueCoordinated(started,ticket,group);
        }));}catch(RuntimeException failure){failStart(failure);}
    }
    @android.annotation.TargetApi(29)
    static WifiP2pConfig groupConfig(DirectGroupCredentials parameters,boolean prefer5){
        WifiP2pConfig.Builder builder=new WifiP2pConfig.Builder().setNetworkName(parameters.ssid).setPassphrase(parameters.password);
        if(parameters.frequency>0)builder.setGroupOperatingFrequency(parameters.frequency);
        else builder.setGroupOperatingBand(prefer5?WifiP2pConfig.GROUP_OWNER_BAND_5GHZ:WifiP2pConfig.GROUP_OWNER_BAND_AUTO);
        return builder.build();
    }
    @android.annotation.TargetApi(29)
    private void issueCoordinated(long started,long ticket,CoordinatedGroup group){
        if(!lifecycle.validAttempt(started,ticket)||coordinated!=group||!group.policy.begin())return;
        WifiDirectPolicy.NativeCommand command;
        try{command=authority.issue(started,ticket);}catch(IllegalStateException unresolved){rejectAttempt("directBusy");return;}
        lastStage=group.owner?"creating_group":"joining_group";
        bandPolicy=!group.owner?"peer_frequency":group.policy.requires5Ghz()?"5ghz_only":group.policy.prefers5Ghz()?"5ghz_preferred":"auto";
        diagnostics.record(group.owner?WifiDirectDiagnostics.Event.CREATE_GROUP_SUBMIT:WifiDirectDiagnostics.Event.JOIN_GROUP_SUBMIT);
        try{
            WifiP2pConfig config=groupConfig(group.parameters,group.policy.prefers5Ghz());
            WifiP2pManager.ActionListener reply=new WifiP2pManager.ActionListener(){
                public void onSuccess(){onMain(()->coordinatedReply(command,group,true,0));}
                public void onFailure(int code){onMain(()->coordinatedReply(command,group,false,code));}
            };
            if(group.owner)manager.createGroup(channel,config,reply);else manager.connect(channel,config,reply);
        }catch(RuntimeException failure){authority.reply(command,false);group.policy.ack(false);rejectAttempt(permissionReason(failure,"directConnectFailed"));}
    }
    private void coordinatedReply(WifiDirectPolicy.NativeCommand command,CoordinatedGroup group,boolean accepted,int code){
        boolean live=authority.reply(command,accepted);completeRetirement(command);
        if(!live||coordinated!=group||!lifecycle.validAttempt(command.run,command.attempt)||!lifecycle.pending()||!group.policy.ack(accepted))return;
        diagnostics.record(group.owner?(accepted?WifiDirectDiagnostics.Event.CREATE_GROUP_ACK:WifiDirectDiagnostics.Event.CREATE_GROUP_FAILED):(accepted?WifiDirectDiagnostics.Event.JOIN_GROUP_ACK:WifiDirectDiagnostics.Event.JOIN_GROUP_FAILED),code);
        if(accepted){lastStage="awaiting_group";inspectGroup();return;}
        if(group.policy.mayFallback()){
            // A confirmed rejection is not enough if another group appeared in the meantime.
            try{manager.requestGroupInfo(channel,current->onMain(()->{
                if(coordinated!=group||!lifecycle.validAttempt(command.run,command.attempt)||!lifecycle.pending())return;
                if(current!=null){rejectAttempt("directForeignGroup");return;}
                if(group.policy.fallback()){diagnostics.record(WifiDirectDiagnostics.Event.GROUP_AUTO_RETRY);issueCoordinated(command.run,command.attempt,group);}
            }));}catch(RuntimeException failure){rejectAttempt(permissionReason(failure,"directConnectFailed"));}
        }else rejectAttempt(reason(code,"directConnectFailed"));
    }
    private void failStart(RuntimeException failure) { stopInternal(); error(failure instanceof SecurityException ? "directPermissionRequired" : "directChannelLost", true); }
    private static String localName(String name) {
        if (name == null || name.trim().isEmpty()) return "wozai";
        String result = name.trim().replaceAll("\\p{Cntrl}", "");
        while (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 96) result = result.substring(0, result.offsetByCodePoints(result.length(), -1));
        return result.isEmpty() ? "wozai" : result;
    }
    private void report(Peer peer) {
        long started = run;
        for (Peer lost : registry.takeLost()) listener.onLost(lost.key);
        if (peer != null && lifecycle.valid(started)) {
            if(fileAttempt.allows(peer)){lastStage="peer_discovered";diagnostics.recordOnce(WifiDirectDiagnostics.Event.TARGET_DISCOVERED);}
            listener.onPeer(peer);
            if (fileAttempt.dial(peer)) connectInternal(peer);
            if (lifecycle.valid(started) && observedGroup == null) inspectGroup();
        }
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
        // Starting a new scan waits for removal of this client's previous DNS request.
        stopSearchInternal(true);
    }
    /** A type-only request is PTR-only; our registry also requires the matching TXT identity. */
    static WifiP2pDnsSdServiceRequest discoveryRequest(){return WifiP2pDnsSdServiceRequest.newInstance();}
    private void beginDiscovery(long started, long search) {
        if (!lifecycle.valid(started) || search != scan || cleanup.blocked()) return;
        var added = requests.add(channel, started, search, discoveryRequest(), now());
        if (added == null) { requestFailure("directSearchStopFailed"); return; }
        lastStage="adding_query";searchRequested = true;diagnostics.record(WifiDirectDiagnostics.Event.QUERY_ADD_SUBMIT);
        WifiP2pManager.Channel discoveredOn = channel;
        main.postDelayed(() -> {
            if (!requests.addTimeout(added, now())) return;
            if (lifecycle.valid(started) && search == scan) { scan++; afterRequests = null; }
            diagnostics.record(WifiDirectDiagnostics.Event.PREPARATION_TIMEOUT);requestFailure("directDiscoveryFailed"); clearRequests();
        }, 4_000);
        try {
            manager.addServiceRequest(discoveredOn, added.value, new WifiP2pManager.ActionListener() {
                public void onSuccess() { onMain(() -> addRequestReply(added, true, 0)); }
                public void onFailure(int code) { onMain(() -> addRequestReply(added, false, code)); }
            });
            main.postDelayed(() -> { if (lifecycle.valid(started) && search == scan && requests.current() == added) stopSearchInternal(false); }, DISCOVERY_MS);
        } catch (RuntimeException failure) {
            addRequestReply(added, false, WifiP2pManager.ERROR);
            if (failure instanceof SecurityException && lifecycle.valid(started)) error("directPermissionRequired", false);
        }
    }
    private void addRequestReply(WifiDirectPolicy.Request<WifiP2pDnsSdServiceRequest> added, boolean success, int code) {
        if (!requests.addPending(added)) return;
        boolean alreadyRetiring = requests.retiring();
        if(lifecycle.valid(added.run)&&added.scan==scan&&!alreadyRetiring)diagnostics.record(success?WifiDirectDiagnostics.Event.QUERY_ADD_ACK:WifiDirectDiagnostics.Event.QUERY_ADD_FAILED,code);
        boolean discover = requests.addReply(added, success);
        if (discover && lifecycle.valid(added.run) && added.scan == scan && searchRequested && channel == added.client) {
            lastStage="starting_scan";diagnostics.record(WifiDirectDiagnostics.Event.SCAN_SUBMIT);
            try { manager.discoverServices(channel, action(added.run, () -> {
                if(added.scan==scan&&searchRequested&&channel==added.client){lastStage="discovering";diagnostics.record(WifiDirectDiagnostics.Event.SCAN_ACK,0);listener.onServiceDiscoveryStarted(Peer.WIFI_DIRECT);}
            }, failure -> {
                if (added.scan == scan) { diagnostics.record(WifiDirectDiagnostics.Event.SCAN_FAILED,failure);stopSearchInternal(false); discoveryError(reason(failure, "directDiscoveryFailed")); }
            })); } catch (RuntimeException failure) {
                if (added.scan == scan) { stopSearchInternal(false); discoveryError(permissionReason(failure, "directDiscoveryFailed")); }
            }
        } else if (!success && !alreadyRetiring) {
            if (lifecycle.valid(added.run) && added.scan == scan) { scan++; afterRequests = null; }
            requestFailure(reason(code, "directDiscoveryFailed")); clearRequests();
        }
        requestsSettled();
    }
    private void discoveryError(String key) {
        if(fileAttempt.armed())listener.onConnectFailed(Peer.WIFI_DIRECT,UiText.of(key));else error(key,false);
    }
    public void stopSearch() { onMain(() -> { automaticDiscovery = false; stopSearchInternal(false); }); }
    private void stopSearchInternal(boolean restart) {
        long search = ++scan, started = run;
        searchRequested = searching = false; listener.onSearching(Peer.WIFI_DIRECT, false);
        if (channel == null || !lifecycle.valid(started)) { afterRequests = null; return; }
        afterRequests = restart ? () -> beginDiscovery(started, search) : () -> scheduleIdleDiscovery(started, search);
        if (requests.occupied()) { if (!clearRequests() && requestCleanupFailed) requestFailure("directSearchStopFailed"); }
        else requestsSettled();
    }
    /** clearServiceRequests targets this Channel's client Messenger, never global P2P discovery. */
    private boolean clearRequests() {
        var clearing = requests.clear(now());
        if (clearing == null) return false; // Unknown clear is fenced; ACKed failure permits a later explicit retry.
        requestCleanupFailed = false;
        diagnostics.record(WifiDirectDiagnostics.Event.QUERY_CLEAR_SUBMIT);
        WifiP2pManager.Channel owner = (WifiP2pManager.Channel) clearing.request.client;
        main.postDelayed(() -> { if (requests.clearTimeout(clearing, now())) requestFailure("directSearchStopFailed"); }, 4_000);
        try { manager.clearServiceRequests(owner, new WifiP2pManager.ActionListener() {
            public void onSuccess() { onMain(() -> {
                if (!requests.clearReply(clearing, true)) return;
                diagnostics.record(WifiDirectDiagnostics.Event.QUERY_CLEAR_ACK,0);
                requestsSettled();
            }); }
            public void onFailure(int code) { onMain(() -> {
                if (requests.clearReply(clearing, false)){diagnostics.record(WifiDirectDiagnostics.Event.QUERY_CLEAR_FAILED,code);requestFailure(reason(code, "directSearchStopFailed"));}
            }); }
        }); } catch (RuntimeException failure) {
            if (requests.clearReply(clearing, false)) requestFailure(permissionReason(failure, "directSearchStopFailed"));
        }
        return true;
    }
    private void requestFailure(String key) {
        requestCleanupFailed = true;
        searchRequested = searching = false; listener.onSearching(Peer.WIFI_DIRECT, false);
        afterRequests = null;
        listener.onSearchStopFailed(Peer.WIFI_DIRECT, UiText.of(key));
        if (afterCleanup != null && !queuedConnect) { afterCleanup = null; error("directCleanupFailed", true); }
    }
    private void requestsSettled() {
        if (requests.occupied()) return;
        requestCleanupFailed = false;
        Runnable next = afterRequests; afterRequests = null;
        if (next != null) next.run();
        finishDeferredAction();
    }
    private boolean finishDeferredAction() {
        if (afterCleanup == null || cleanup.blocked() || !queuedConnect && requests.occupied()) return false;
        Runnable next = afterCleanup; afterCleanup = null; queuedConnect = false;
        if (!destroyed) next.run();
        return true;
    }
    private void scheduleIdleDiscovery(long started, long search) {
        if (!automaticDiscovery || !lifecycle.valid(started)) return;
        main.postDelayed(() -> {
            if (automaticDiscovery && lifecycle.valid(started) && search == scan && !cleanup.blocked() && !lifecycle.pending() && !authority.hasGroup() && !sockets.hasDelivered()) discoverInternal();
        }, 15_000);
    }

    public void connect(Peer peer) { onMain(() -> connectInternal(peer)); }
    private void connectInternal(Peer peer) {
        if (!lifecycle.valid(run) || !initialized || !advertised) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directBusy")); return; }
        if (!permitted()) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directPermissionRequired")); return; }
        Peer retained = peer == null ? null : authority.currentPeer(peer.peerId);
        boolean sameHint = retained != null && Objects.equals(retained.wifiDirectAddress, peer.wifiDirectAddress);
        boolean fresh = registry != null && registry.contains(peer, now());
        if (!fresh && !sameHint) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directInvalidPeer")); return; }
        if (sockets.hasDelivered() || lifecycle.pending()) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directBusy")); return; }
        if (cleanup.blocked()) {
            if (cleanupWork != null && cleanupWork.failed) { listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of("directCleanupFailed")); return; }
            long wanted = operation, started = run;
            queuedConnect = true; afterCleanup = () -> { if (wanted == operation && lifecycle.valid(started)) connectInternal(peer); }; return;
        }
        selected = peer; final long started = run, ticket = attempt = lifecycle.beginAttempt();
        invalidateInspection(); closeGroupStreams();
        connectDeadline = () -> { if (lifecycle.validAttempt(started, ticket) && lifecycle.pending()) failAttempt("directConnectTimeout"); };
        main.postDelayed(connectDeadline, CONNECT_MS);
        try { manager.requestGroupInfo(channel, group -> onMain(() -> {
            if (!lifecycle.validAttempt(started, ticket) || !lifecycle.pending()) return;
            // Query before every explicit attempt: connect() may invite into an existing group.
            // A retained hint is usable only when this exact observed group still matches.
            if (observedGroup != null && !observedGroup.matches(group)) forgetObservedGroup();
            if (group == null) { forgetObservedGroup(); foreignReported = false; unprovenGroup = null; unprovenRetry = false; }
            boolean matches = groupHasPeer(group, peer.wifiDirectAddress);
            WifiDirectPolicy.Admission admission = authority.admit(peer, group != null, matches, fresh);
            if (admission == WifiDirectPolicy.Admission.REUSE_GROUP) { inspectGroup(); return; }
            if (admission != WifiDirectPolicy.Admission.NEGOTIATE) {
                rejectAttempt(admission == WifiDirectPolicy.Admission.FOREIGN_GROUP ? "directForeignGroup" : "directInvalidPeer"); return;
            }
            issueConnect(started, ticket, peer);
        })); } catch (RuntimeException failure) { rejectAttempt(permissionReason(failure, "directConnectFailed")); }
    }
    private void issueConnect(long started, long ticket, Peer peer) {
        WifiDirectPolicy.NativeCommand command;
        try { command = authority.issue(started, ticket); }
        catch (IllegalStateException unresolved) { rejectAttempt("directBusy"); return; }
        lastStage="connecting_group";
        diagnostics.record(WifiDirectDiagnostics.Event.CONNECT_SUBMIT);
        WifiP2pConfig config = new WifiP2pConfig(); config.deviceAddress = peer.wifiDirectAddress; config.wps.setup = WpsInfo.PBC;
        try { manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            public void onSuccess() { onMain(() -> connectReply(command, true, 0)); }
            public void onFailure(int code) { onMain(() -> connectReply(command, false, code)); }
        }); } catch (RuntimeException failure) {
            authority.reply(command, false); rejectAttempt(permissionReason(failure, "directConnectFailed"));
        }
    }
    private void connectReply(WifiDirectPolicy.NativeCommand command, boolean accepted, int code) {
        boolean live = authority.reply(command, accepted);
        completeRetirement(command);
        if (!live || !lifecycle.validAttempt(command.run, command.attempt) || !lifecycle.pending()) return;
        diagnostics.record(accepted?WifiDirectDiagnostics.Event.CONNECT_ACK:WifiDirectDiagnostics.Event.CONNECT_FAILED,code);
        if (accepted) {lastStage="awaiting_group";inspectGroup();} else rejectAttempt(reason(code, "directConnectFailed"));
    }
    public void cancelConnect() { onMain(() -> { if (queuedConnect) { afterCleanup = null; queuedConnect = false; } cancelInternal(); }); }
    private void cancelInternal() {
        if (!lifecycle.pending()) return;
        stopCoordinated();
        WifiDirectPolicy.Release release = authority.release(true, false, now());
        lifecycle.cancelAttempt(); selected = null;
        clearDeadline(); invalidateInspection(); closeGroupStreams(); forgetObservedGroup();
        stopSearchInternal(false);
        beginRetirement(channel, release, false);
    }
    private void failAttempt(String key) {
        if (!lifecycle.pending()) return;
        cancelInternal(); listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of(key));
    }
    private void rejectAttempt(String key) {
        if (!lifecycle.pending()) return;
        stopCoordinated();
        // Rejection changes this attempt only; native radio state belongs to the system.
        WifiDirectPolicy.Release release = authority.release(true, false, now());
        lifecycle.cancelAttempt(); selected = null;
        clearDeadline(); invalidateInspection(); closeGroupStreams(); forgetObservedGroup();
        beginRetirement(channel, release, false);
        scheduleIdleDiscovery(run, scan);
        listener.onConnectFailed(Peer.WIFI_DIRECT, UiText.of(key));
    }
    public void disconnect() { onMain(this::disconnectInternal); }
    private void disconnectInternal() {
        if(coordinated!=null){stopInternal();return;}
        fileAttempt.stop();
        if (queuedConnect) { afterCleanup = null; queuedConnect = false; }
        if (lifecycle.pending()) { cancelInternal(); return; }
        WifiDirectPolicy.Release release = authority.release(false, false, now());
        closeGroupStreams(); invalidateInspection(); selected = null;
        // Preserve a healthy current-group hint for an explicit retry, without automatically dialing.
        beginRetirement(channel, release, false);
        if (lifecycle.valid(run)) { listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising")); scheduleIdleDiscovery(run, scan); }
    }

    @SuppressWarnings("deprecation")
    private BroadcastReceiver receiverFor(long started) { return new BroadcastReceiver() {
        public void onReceive(Context ignored, Intent intent) {
            if (!lifecycle.valid(started)) return;
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                observedP2pState=intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                if (observedP2pState != WifiP2pManager.WIFI_P2P_STATE_ENABLED) { stopInternal(); error("directEnableFirst", true); }
            } else if (WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {
                boolean active = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED;
                if(searchRequested)diagnostics.record(active?WifiDirectDiagnostics.Event.DISCOVERY_STARTED:WifiDirectDiagnostics.Event.DISCOVERY_STOPPED);
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
        if(fileAttempt.armed())authority.rearmFile();
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
        if(coordinated!=null){handleCoordinatedGroup(started,info,group);return;}
        if (info == null || !info.groupFormed || group == null) {
            if (observedGroup != null || readyGroup != null || foreignReported || unprovenGroup != null) groupLost();
            return;
        }
        lastStage="group_observed";
        diagnostics.recordOnce(WifiDirectDiagnostics.Event.GROUP_OBSERVED);
        if (info.isGroupOwner != group.isGroupOwner() || info.groupOwnerAddress == null) { routeFailure("directInterfaceUnavailable"); return; }
        if (observedGroup != null && !observedGroup.matches(group)) { closeGroupStreams(); forgetObservedGroup(); }
        // A canceled native command may still form a group. Only a fresh explicit attempt can
        // authorize sockets after retirement; a delayed broadcast/service hint cannot resurrect it.
        if (!authority.mayOpen(lifecycle.pending()) && observedGroup == null) return;
        Peer counterpart = verifiedCounterpart(group);
        if (counterpart != null && !fileAttempt.allows(counterpart)) return;
        if (counterpart == null) {
            closeGroupStreams();
            String key = group.getInterface() + "/" + group.getNetworkName() + "/" + group.isGroupOwner();
            if (!key.equals(unprovenGroup)) {
                unprovenGroup = key; unprovenDeadline = now() + INTERFACE_MS;
                // An idle advertised receiver may not have heard this peer recently. Seek bounded
                // app-service provenance; an invitation/group name never proves radio authority.
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
            return; // Preserve unrelated or unproven groups. Advertising never grants removal authority.
        }
        unprovenGroup = null; unprovenRetry = false;
        foreignReported = false;
        GroupIdentity identity = new GroupIdentity(group, counterpart);
        if (!lifecycle.observeGroup(started, true)) return;
        observedGroup = identity; authority.observe(counterpart);
        if (searchRequested || searching) stopSearchInternal(false);
        if (!authority.mayOpen(lifecycle.pending())) return;
        String key = identity.key() + "/" + info.groupOwnerAddress.getHostAddress();
        if (key.equals(readyGroup)) return;
        readyGroup = key; final long generation = ++groupGeneration, ticket = lifecycle.pending() ? attempt : 0;
        sockets.reset(); unregisterNetwork();
        readyInterface(started, generation, ticket, group.getInterface(), info.groupOwnerAddress, info.isGroupOwner, now() + INTERFACE_MS);
    }
    private void handleCoordinatedGroup(long started,WifiP2pInfo info,WifiP2pGroup group){
        CoordinatedGroup current=coordinated;
        if((info==null||!info.groupFormed||group==null)&&current.observed){routeFailure("directGroupLost");return;}
        if(group==null){
            // The native connect/create budget still bounds group formation. Once IP setup has
            // begun, a disappearing snapshot must also retain that original readiness deadline.
            waitCoordinated(started,current,current.readiness.waiting()?current.readiness.observe(now(),false,false,false,false):WifiDirectPolicy.GroupReadiness.State.WAIT_GROUP);return;
        }
        // ACK, SSID and role identify this fresh network; the later Noise lane proves the peer.
        if(!current.policy.matches(group.getNetworkName(),group.isGroupOwner())){
            if(!current.parameters.ssid.equals(group.getNetworkName())||current.owner!=group.isGroupOwner())routeFailure("directForeignGroup");
            return;
        }
        if(Build.VERSION.SDK_INT>=29){int frequency=group.getFrequency();groupFrequency=frequency>=2400&&frequency<=2500||frequency>=4900&&frequency<=7125?frequency:0;}
        boolean acceptedBand=current.policy.acceptsFrequency(groupFrequency);
        if(!acceptedBand&&groupFrequency!=0){lastStage="band_mismatch";diagnostics.record(WifiDirectDiagnostics.Event.GROUP_BAND_REJECTED);routeFailure("directConnectFailed");return;}
        WifiDirectPolicy.GroupReadiness.State readiness=current.readiness.observe(now(),info!=null&&info.groupFormed,info!=null&&info.isGroupOwner==current.owner,
                info!=null&&info.groupOwnerAddress!=null&&!info.groupOwnerAddress.isAnyLocalAddress(),group.getInterface()!=null&&!group.getInterface().isEmpty(),acceptedBand);
        if(readiness!=WifiDirectPolicy.GroupReadiness.State.READY){waitCoordinated(started,current,readiness);return;}
        clearCoordinatedRetry(current);
        current.observed=true;diagnostics.recordOnce(WifiDirectDiagnostics.Event.GROUP_OBSERVED);lastStage="group_observed";
        coordinatedInterface=group.getInterface();
        String key=group.getInterface()+"/"+group.getNetworkName()+"/"+current.owner+"/"+info.groupOwnerAddress.getHostAddress();
        if(key.equals(readyGroup))return;
        readyGroup=key;long generation=++groupGeneration;sockets.reset();unregisterNetwork();
        readyInterface(started,generation,lifecycle.pending()?attempt:0,group.getInterface(),info.groupOwnerAddress,current.owner,now()+INTERFACE_MS);
    }
    private void waitCoordinated(long started,CoordinatedGroup group,WifiDirectPolicy.GroupReadiness.State state){
        if(state==WifiDirectPolicy.GroupReadiness.State.RETIRED)return;
        if(state==WifiDirectPolicy.GroupReadiness.State.EXPIRED){diagnostics.record(WifiDirectDiagnostics.Event.GROUP_READINESS_TIMEOUT);routeFailure("directInterfaceUnavailable");return;}
        lastStage=state.name().toLowerCase(Locale.ROOT);
        diagnostics.recordOnce(state==WifiDirectPolicy.GroupReadiness.State.WAIT_ADDRESS?WifiDirectDiagnostics.Event.GROUP_WAIT_ADDRESS:
                state==WifiDirectPolicy.GroupReadiness.State.WAIT_ROLE?WifiDirectDiagnostics.Event.GROUP_WAIT_ROLE:
                state==WifiDirectPolicy.GroupReadiness.State.WAIT_INTERFACE?WifiDirectDiagnostics.Event.GROUP_WAIT_INTERFACE:
                state==WifiDirectPolicy.GroupReadiness.State.WAIT_FREQUENCY?WifiDirectDiagnostics.Event.GROUP_WAIT_FREQUENCY:WifiDirectDiagnostics.Event.GROUP_WAIT_INFO);
        long ticket=attempt;
        // A missing native query callback must not turn the IP budget into the total connect budget.
        if(group.readiness.waiting()&&group.timeout==null){
            group.timeout=()->{group.timeout=null;if(coordinated==group&&lifecycle.validAttempt(started,ticket)&&!cleanup.blocked()&&group.readiness.waiting())waitCoordinated(started,group,group.readiness.observe(now(),false,false,false,false));};
            main.postDelayed(group.timeout,group.readiness.remaining(now()));
        }
        if(group.retry!=null)return;
        group.retry=()->{group.retry=null;if(coordinated==group&&lifecycle.validAttempt(started,ticket)&&!cleanup.blocked())inspectGroup();};
        main.postDelayed(group.retry,250);
    }
    private void clearCoordinatedRetry(CoordinatedGroup group){if(group.retry!=null)main.removeCallbacks(group.retry);if(group.timeout!=null)main.removeCallbacks(group.timeout);group.retry=group.timeout=null;}
    private void stopCoordinated(){if(coordinated!=null){coordinated.policy.stop();coordinated.readiness.stop();clearCoordinatedRetry(coordinated);}}
    private Peer verifiedCounterpart(WifiP2pGroup group) {
        if (observedGroup != null && observedGroup.matches(group)) {
            Peer retained = authority.currentPeer(observedGroup.peerId);
            if (retained != null && (!lifecycle.pending() || selected == null || retained.peerId.equals(selected.peerId) && retained.wifiDirectAddress.equals(selected.wifiDirectAddress))) return retained;
        }
        Collection<WifiP2pDevice> devices = group.isGroupOwner() ? group.getClientList() : Collections.singletonList(group.getOwner());
        for (WifiP2pDevice device : devices) {
            if (device == null) continue;
            String verified = WifiDirectPolicy.counterpart(device.deviceAddress, selected, lifecycle.pending(), registry, now());
            if (verified != null) {
                if (lifecycle.pending() && selected != null && verified.equals(selected.wifiDirectAddress)) return selected;
                return registry.byAddress(verified, now());
            }
        }
        return null;
    }
    private static final class GroupIdentity {
        final String iface, network, peer, peerId; final boolean go;
        GroupIdentity(WifiP2pGroup group, Peer peer) { iface = group.getInterface(); network = group.getNetworkName(); go = group.isGroupOwner(); this.peer = peer.wifiDirectAddress; peerId = peer.peerId; }
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
                    if (snapshot.event != null) diagnostics.recordOnce(snapshot.event);
                    if (snapshot.route != null) openSockets(started, generation, ticket, snapshot, owner, go);
                    else if (snapshot.failure != null) routeFailure(snapshot.failure);
                    else if (now() >= deadline) routeFailure("directInterfaceUnavailable");
                    else main.postDelayed(() -> readyInterface(started, generation, ticket, iface, owner, go, deadline), 250);
                });
            });
        } catch (RejectedExecutionException busy) { routeFailure("directBusy"); }
    }
    private boolean groupCurrent(long started, long generation, long ticket) {
        if(coordinated!=null)return lifecycle.valid(started)&&generation==groupGeneration&&!cleanup.blocked()&&coordinated.observed&&coordinated.policy.matches(coordinated.parameters.ssid,coordinated.owner)&&(ticket==0||lifecycle.validAttempt(started,ticket));
        return lifecycle.valid(started) && generation == groupGeneration && !cleanup.blocked() && authority.mayOpen(lifecycle.pending()) && (ticket == 0 || lifecycle.validAttempt(started, ticket));
    }
    private static final class RouteSnapshot { final WifiDirectPolicy.Route route; final Network network; final String failure; final WifiDirectDiagnostics.Event event;
        RouteSnapshot(WifiDirectPolicy.Route route, Network network, String failure) { this(route, network, failure, null); }
        RouteSnapshot(WifiDirectPolicy.Route route, Network network, String failure, WifiDirectDiagnostics.Event event) { this.route = route; this.network = network; this.failure = failure; this.event = event; }
    }
    private RouteSnapshot inspectRoute(String iface, InetAddress owner, boolean go) {
        if (!(owner instanceof Inet4Address)) return new RouteSnapshot(null, null, "directIpv6Unsupported");
        try {
            List<WifiDirectPolicy.InterfaceRoute> interfaces = new ArrayList<>();
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                for (InterfaceAddress address : networkInterface.getInterfaceAddresses()) interfaces.add(new WifiDirectPolicy.InterfaceRoute(networkInterface.getName(), address.getAddress(), address.getNetworkPrefixLength()));
            }
            WifiDirectPolicy.Route proof = WifiDirectPolicy.localRoute(iface, owner, go, interfaces);
            if (proof == null) return new RouteSnapshot(null, null, null, WifiDirectDiagnostics.Event.ROUTE_WAIT_INTERFACE); // The actual interface is not assigned yet.
            Network matching = null; boolean vpn = false;
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (networkInterface.isUp() && !networkInterface.isLoopback() && networkInterface.isPointToPoint()) vpn = true;
            }
            if (connectivity != null) for (Network network : connectivity.getAllNetworks()) {
                NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network); LinkProperties properties = connectivity.getLinkProperties(network);
                if (capabilities == null) continue;
                vpn |= capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
                // The GO is a passive, address-bound listener. It needs no Network
                // object and must not depend on how ConnectivityService exposes P2P.
                if (go) continue;
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || properties == null || !Objects.equals(iface, properties.getInterfaceName())) continue;
                boolean local = false, route = false;
                for (LinkAddress address : properties.getLinkAddresses()) if (proof.local.equals(address.getAddress()) && proof.prefix == address.getPrefixLength()) local = true;
                for (RouteInfo candidate : properties.getRoutes()) if (candidate.getDestination().getPrefixLength() > 0 && candidate.getDestination().contains(owner)) route = true;
                if (local && route) { if (matching != null) return new RouteSnapshot(null, null, "directRouteAmbiguous", WifiDirectDiagnostics.Event.ROUTE_NETWORK_AMBIGUOUS); matching = network; }
            }
            // The GC's outgoing socket needs an exact Network when a VPN is present.
            // GO accepted sockets keep Android's ingress-network mark; still reject
            // competing interface subnets before listening on the actual owner IP.
            WifiDirectPolicy.Route accepted = WifiDirectPolicy.prove(iface, owner, go, interfaces, !go && matching != null, vpn);
            WifiDirectDiagnostics.Event event;
            if (accepted == null) event = !go && vpn && matching == null ? WifiDirectDiagnostics.Event.ROUTE_VPN_UNBOUND : WifiDirectDiagnostics.Event.ROUTE_SUBNET_OVERLAP;
            else if (go) event = vpn ? WifiDirectDiagnostics.Event.ROUTE_OWNER_VPN_LISTENER : WifiDirectDiagnostics.Event.ROUTE_OWNER_LISTENER;
            else event = matching != null ? WifiDirectDiagnostics.Event.ROUTE_NETWORK_BOUND : WifiDirectDiagnostics.Event.ROUTE_SOURCE_BOUND;
            return new RouteSnapshot(accepted, matching, accepted == null ? "directRouteAmbiguous" : null, event);
        } catch (SecurityException denied) { return new RouteSnapshot(null, null, "directPermissionRequired"); }
        catch (SocketException | RuntimeException unavailable) { return new RouteSnapshot(null, null, null); }
    }
    private void openSockets(long started, long generation, long ticket, RouteSnapshot route, InetAddress owner, boolean go) {
        if (!groupCurrent(started, generation, ticket)) return;
        lastStage="opening_socket";
        final long socketGeneration = sockets.reset();
        try { monitorNetwork(started, generation, route.network); } catch (RuntimeException denied) { diagnostics.recordOnce(WifiDirectDiagnostics.Event.ROUTE_MONITOR_FAILED); routeFailure(permissionReason(denied, "directRouteAmbiguous")); return; }
        WifiDirectSockets.Events events = new WifiDirectSockets.Events() {
            public void onReady(int port) { onMain(() -> {
                if (groupCurrent(started, generation, ticket) && sockets.current(socketGeneration)) {
                    CoordinatedGroup group=coordinated;
                    if(group!=null&&group.policy.announce()){diagnostics.record(WifiDirectDiagnostics.Event.GROUP_PARAMETERS_SENT);group.ready.accept(group.parameters.withFrequency(groupFrequency));}
                    listener.onListening(Peer.WIFI_DIRECT, UiText.of("directListening", LanTransport.endpoint(route.route.local, port)));
                }
            }); }
            public void onConnection(StreamConnection connection, boolean incoming) { onMain(() -> {
                if (!groupCurrent(started, generation, ticket) || !sockets.current(socketGeneration)) { close(connection); return; }
                if (ticket != 0) { lifecycle.connected(started, ticket); selected = null; clearDeadline(); }
                // incoming is TCP accept / Noise responder. It never expresses the user's request.
                lastStage="socket_opened";listener.onConnection(Peer.WIFI_DIRECT, connection, incoming);
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
        boolean delivered = sockets.hasDelivered(), observed = authority.hasGroup();
        closeGroupStreams(); invalidateInspection(); forgetObservedGroup();
        unprovenGroup = null; unprovenRetry = false; foreignReported = false;
        if (!lifecycle.pending()) selected = null;
        if (lifecycle.pending() && observed) rejectAttempt("directGroupLost");
        else if (delivered) error("directGroupLost", false);
        if (observed && lifecycle.valid(run)) listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising"));
        scheduleIdleDiscovery(run, scan);
    }
    private void forgetObservedGroup() { observedGroup = null; authority.loseGroup(); lifecycle.loseGroup(run); }
    private void closeGroupStreams() { groupGeneration++; readyGroup = null; sockets.reset(); unregisterNetwork(); }
    private void clearDeadline() { if (connectDeadline != null) main.removeCallbacks(connectDeadline); connectDeadline = null; }
    private void clearStartupWatchdog() { if (startupWatchdog != null) main.removeCallbacks(startupWatchdog); startupWatchdog = null; }

    public void stop() { onMain(this::stopInternal); }
    private void stopInternal() {
        stopCoordinated();coordinated=null;coordinatedInterface=null;
        fileAttempt.stop();
        operation++; afterCleanup = afterRequests = null; queuedConnect = false; automaticDiscovery = false; unprovenGroup = null; unprovenRetry = false; foreignReported = false;
        WifiDirectPolicy.Release release = authority.release(lifecycle.pending(), true, now());
        WifiP2pManager.Channel previous = channel; WifiP2pDnsSdServiceInfo service = advertisement;
        lifecycle.stop(); run = 0; initialized = advertised = false; selected = null; observedGroup = null; channel = null; advertisement = null; registry = null;
        clearDeadline(); clearStartupWatchdog(); invalidateInspection(); closeGroupStreams(); scan++; searchRequested = searching = false;
        if (receiverRegistered) { try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) {} receiverRegistered = false; } receiver = null;
        if (requests.occupied()) clearRequests();
        if (previous != null) {
            if (service != null) try { manager.removeLocalService(previous, service, null); } catch (RuntimeException ignored) {}
            beginRetirement(previous, release, true);
        }
        listener.onSearching(Peer.WIFI_DIRECT, false);
    }
    /** Retire our command/client, never globally cancel negotiation or remove an observed group. */
    private void beginRetirement(WifiP2pManager.Channel old, WifiDirectPolicy.Release release, boolean closeClient) {
        if (old == null) return;
        if (cleanup.blocked()) {
            if (cleanupWork != null && cleanupWork.channel == old) cleanupWork.closeChannel |= closeClient;
            return;
        }
        if (release == WifiDirectPolicy.Release.LOCAL_ONLY) { if (closeClient) closeChannel(old); return; }
        long token = cleanup.begin();
        CleanupWork work = cleanupWork = new CleanupWork(token, old, authority.currentCommand(), closeClient);
        main.postDelayed(() -> {
            if (!cleanup.current(token) || cleanupWork != work || !authority.timeout(work.command, now())) return;
            work.failed = true; afterCleanup = null; queuedConnect = false;
            if (work.closeChannel) closeChannel(old);
            error("directCleanupFailed", false);
        }, 4_000);
    }
    private void completeRetirement(WifiDirectPolicy.NativeCommand command) {
        CleanupWork work = cleanupWork;
        if (work == null || work.command != command || authority.blocked() || !cleanup.current(work.token)) return;
        if (work.closeChannel) closeChannel(work.channel);
        if (!cleanup.complete(work.token)) return;
        cleanupWork = null;
        if(fileAttempt.armed())authority.rearmFile();
        if (!finishDeferredAction() && afterCleanup == null && lifecycle.valid(run)) { listener.onListening(Peer.WIFI_DIRECT, UiText.of("directAdvertising")); scheduleIdleDiscovery(run, scan); }
    }
    private static final class CleanupWork {
        final long token; final WifiP2pManager.Channel channel; final WifiDirectPolicy.NativeCommand command;
        boolean closeChannel, failed;
        CleanupWork(long token, WifiP2pManager.Channel channel, WifiDirectPolicy.NativeCommand command, boolean closeChannel) {
            this.token = token; this.channel = channel; this.command = command; this.closeChannel = closeChannel;
        }
    }
    private static boolean groupHasPeer(WifiP2pGroup group, String address) {
        if (group == null || address == null) return false;
        Collection<WifiP2pDevice> devices = group.isGroupOwner() ? group.getClientList() : Collections.singletonList(group.getOwner());
        for (WifiP2pDevice device : devices) if (device != null && address.equals(WifiDirectPolicy.address(device.deviceAddress))) return true;
        return false;
    }
    private void closeChannel(WifiP2pManager.Channel client) {
        if (Build.VERSION.SDK_INT < 27) return;
        try {
            client.close(); // Public own-client retirement; the OS may tear down its last client.
            requests.closed(client); requestsSettled();
        } catch (RuntimeException ignored) {} // Unsupported/failed close cannot settle an uncertain slot.
    }
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
