package dev.ghost.nearbyim.transport;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.*;
import android.os.*;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent file sockets. The coordinator, not this address layer, proves identity/consent. */
public final class AndroidFilePlatform implements AutomaticFileRoutes.Platform {
    private final Context context;
    private final String localId, nickname, remoteId;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService acceptor = Executors.newSingleThreadExecutor();
    private final Semaphore incomingSlots = new Semaphore(2);
    private final WifiDirectTransport direct;
    private final LanTransport lanDial;
    private volatile boolean closed;
    private volatile ServerSocket server;
    private volatile Set<InetAddress> lanAddresses = Set.of();
    private final ConcurrentHashMap<StreamConnection,InetAddress> acceptedStreams=new ConcurrentHashMap<>();
    private ConnectivityManager.NetworkCallback networkCallback;
    private Consumer<List<AutomaticFileRoutes.Endpoint>> endpointUpdates;
    private Consumer<StreamConnection> lanOpened;
    private Consumer<Throwable> lanFailed;
    private List<AutomaticFileRoutes.Endpoint> lanCandidates = List.of();
    private int lanCandidate;
    private Consumer<AutomaticFileRoutes.Opened> directOpened;
    private Consumer<Throwable> directFailed;
    private long directAttempt;
    private Runnable directTimeout,discoveryTimeout;
    private final WifiDirectPolicy.DiscoveryDeadline discoveryBudget=new WifiDirectPolicy.DiscoveryDeadline(AutomaticFileRoutes.DIRECT_DISCOVERY_PREPARE_TIMEOUT_MS,AutomaticFileRoutes.DIRECT_DISCOVERY_TIMEOUT_MS);
    private boolean directStarted,directPeerSeen,directGroupMode;
    private String preparedGroupNonce;
    private volatile String directLastEvent="idle",directLastError="";

    public AndroidFilePlatform(Context context,String localId,String nickname,String remoteId) {
        this.context=context.getApplicationContext();this.localId=localId;this.nickname=nickname;this.remoteId=remoteId;
        direct=new WifiDirectTransport(context,new Events(){
            public void onConnection(int mode,StreamConnection stream,boolean incoming) {
                Consumer<AutomaticFileRoutes.Opened> target=directOpened;
                if(closed||target==null){closeStream(stream);return;}
                directLastEvent="socket_opened";direct.diagnostics.record(WifiDirectDiagnostics.Event.SOCKET_OPENED);direct.diagnostics.stop();discoveryBudget.stop();clearDiscoveryTimeout();clearDirectTimeout(); directOpened=null;directFailed=null;
                target.accept(new AutomaticFileRoutes.Opened(stream,incoming));
            }
            public void onPeer(Peer peer){if(directOpened!=null&&remoteId.equals(peer.peerId)){directPeerSeen=true;directLastEvent="peer_discovered";discoveryBudget.found();clearDiscoveryTimeout();}}
            public void onListening(int mode,UiText detail){if(directOpened!=null){directLastEvent=detail.key;if(detail.key.equals("directAdvertising"))armDiscoveryPreparation();}}
            public void onServiceDiscoveryStarted(int mode){if(directOpened!=null&&!directPeerSeen&&discoveryBudget.scan(SystemClock.elapsedRealtime())){directLastEvent="discovery_started";scheduleDiscoveryTimeout();}}
            public void onError(int mode,UiText reason,boolean fatal){if(fatal)directStarted=false;if(directOpened==null)return;String code=directError(reason);directLastEvent=code;directLastError=code;if(fatal||!reason.key.equals("directBusy"))failDirect(new IOException(code));}
            public void onSearchStopFailed(int mode,UiText reason){if(directOpened==null)return;String code=directError(reason);directLastEvent=code;directLastError=code;failDirect(new IOException(code));}
            public void onConnectFailed(int mode,UiText reason){if(directOpened==null)return;String code=directError(reason);directLastEvent=code;directLastError=code;failDirect(new IOException(code));}
        });
        lanDial=new LanTransport(context,new Events(){
            public void onConnection(int mode,StreamConnection stream,boolean incoming) {
                Consumer<StreamConnection> target=lanOpened;
                if(closed||target==null){closeStream(stream);return;}
                lanOpened=null;lanFailed=null;lanCandidates=List.of();target.accept(stream);
            }
            public void onConnectFailed(int mode,UiText reason){nextLan();}
            public void onError(int mode,UiText reason,boolean fatal){if(fatal)nextLan();}
        });
    }
    public boolean directSupported(){return directUnavailableReason().isEmpty();}
    public boolean directGroupsSupported(){return Build.VERSION.SDK_INT>=29&&directSupported();}
    public boolean direct5GhzSupported(){return direct.fiveGhzSupported();}
    public void prepareDirectGroup(String nonce,boolean owner){onMain(()->prepareGroup(nonce,owner));}
    private void prepareGroup(String nonce,boolean owner){
        if(closed)return;stopDirect();direct.stop();directStarted=false;directGroupMode=true;preparedGroupNonce=nonce;
        direct.beginKnownGroupDiagnostics(nonce,owner);directLastEvent=owner?"preparing_group":"waiting_group_parameters";directLastError="";
    }
    public void createDirectGroup(String nonce,boolean prefer5Ghz,Consumer<DirectGroupCredentials> advertised,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){
        createDirectGroup(nonce,prefer5Ghz,true,advertised,opened,failed);
    }
    public void createDirectGroup(String nonce,boolean prefer5Ghz,boolean allowAutoFallback,Consumer<DirectGroupCredentials> advertised,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){
        openGroup(nonce,true,prefer5Ghz,allowAutoFallback,DirectGroupCredentials.random(),advertised,opened,failed);
    }
    public void joinDirectGroup(String nonce,DirectGroupCredentials parameters,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){
        openGroup(nonce,false,parameters.frequency>=4900&&parameters.frequency<5925,false,parameters,unused->{},opened,failed);
    }
    private void openGroup(String nonce,boolean owner,boolean prefer5Ghz,boolean allowAutoFallback,DirectGroupCredentials parameters,Consumer<DirectGroupCredentials> advertised,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){
        onMain(()->{
            if(closed||!directGroupsSupported()){failed.accept(new IOException("Explicit P2P group unavailable"));return;}
            if(!nonce.equals(preparedGroupNonce))prepareGroup(nonce,owner);
            directGroupMode=true;directStarted=true;
            directOpened=opened;directFailed=failed;directPeerSeen=true;directLastEvent=owner?"creating_group":"joining_group";directLastError="";long ticket=++directAttempt;
            if(!owner)direct.diagnostics.record(WifiDirectDiagnostics.Event.GROUP_PARAMETERS_RECEIVED);
            directTimeout=()->{if(ticket==directAttempt&&directOpened!=null){directLastEvent="timeout";directLastError="timeout";direct.diagnostics.record(WifiDirectDiagnostics.Event.OPEN_TIMEOUT);failDirect(new AutomaticFileRoutes.DirectFailure(AutomaticFileRoutes.DirectFailureReason.OPEN_TIMEOUT));}};
            main.postDelayed(directTimeout,AutomaticFileRoutes.DIRECT_OPEN_TIMEOUT_MS);
            direct.startGroup(localId,nickname,remoteId,owner,prefer5Ghz,allowAutoFallback,parameters,advertised);
        });
    }
    public String directUnavailableReason() {
        try{
            if(!direct.available())return "unsupported";
            String permission=Build.VERSION.SDK_INT>=33?Manifest.permission.NEARBY_WIFI_DEVICES:Manifest.permission.ACCESS_FINE_LOCATION;
            if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)return "permission_missing";
            if(Build.VERSION.SDK_INT<33){android.location.LocationManager location=context.getSystemService(android.location.LocationManager.class);if(location==null||Build.VERSION.SDK_INT>=28&&!location.isLocationEnabled())return "location_disabled";}
            return "";
        }catch(RuntimeException unavailable){return "system_state_unavailable";}
    }
    public String diagnostics(){
        String location="unknown";
        try{android.location.LocationManager manager=context.getSystemService(android.location.LocationManager.class);if(manager!=null&&Build.VERSION.SDK_INT>=28)location=Boolean.toString(manager.isLocationEnabled());}catch(RuntimeException unavailable){}
        return "wifi_direct_discovery_deadline_ms="+AutomaticFileRoutes.DIRECT_DISCOVERY_TIMEOUT_MS+"\nwifi_direct_discovery_prepare_deadline_ms="+AutomaticFileRoutes.DIRECT_DISCOVERY_PREPARE_TIMEOUT_MS+"\n"+"wifi_direct_blocked_reason="+directUnavailableReason()+"\nwifi_direct_station_wifi_enabled="+direct.enabled()+"\nwifi_direct_p2p_state="+direct.p2pState()+"\nwifi_direct_location_enabled="+location+"\nwifi_direct_last_event="+directLastEvent+"\nwifi_direct_last_error="+directLastError+"\n"+direct.discoveryDiagnostics();
    }
    public void prepareLan(Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints,Consumer<StreamConnection> accepted) {
        if(closed){endpoints.accept(List.of());return;}
        onMain(()->{endpointUpdates=endpoints;monitorLan();});
        acceptor.execute(()->{
            ServerSocket opened=null;
            try {
                opened=new ServerSocket(0,2);server=opened;
                if(closed){opened.close();return;}
                List<AutomaticFileRoutes.Endpoint> advertised=lanEndpoints(opened.getLocalPort());
                main.post(()->{if(!closed)endpoints.accept(advertised);});
                while(!closed){
                    Socket socket=opened.accept();
                    if(!LocalEndpoint.isLocal(socket.getInetAddress())||!lanAddresses.contains(socket.getLocalAddress())||!incomingSlots.tryAcquire()){socket.close();continue;}
                    try{socket.setTcpNoDelay(true);socket.setKeepAlive(true);}catch(IOException|RuntimeException unavailable){socket.close();incomingSlots.release();continue;}
                    StreamConnection stream=new StreamConnection(){
                        private final java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
                        public InputStream input()throws IOException{return socket.getInputStream();}
                        public OutputStream output()throws IOException{return socket.getOutputStream();}
                        public String label(){return "LAN file";}
                        public void close()throws IOException{if(ended.compareAndSet(false,true))try{socket.close();}finally{acceptedStreams.remove(this);incomingSlots.release();}}
                    };
                    acceptedStreams.put(stream,socket.getLocalAddress());
                    main.post(()->{if(closed)closeStream(stream);else accepted.accept(stream);});
                }
            }catch(IOException|RuntimeException failure){main.post(()->{if(!closed)endpoints.accept(List.of());});}
            finally{if(opened!=null)try{opened.close();}catch(IOException ignored){}}
        });
    }
    private List<AutomaticFileRoutes.Endpoint> lanEndpoints(int port)throws SocketException {
        List<AutomaticFileRoutes.Endpoint> result=new ArrayList<>();Set<InetAddress> allowed=new HashSet<>();
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);
        if(manager!=null)for(Network network:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(network);LinkProperties props=manager.getLinkProperties(network);
            if(caps==null||props==null||caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))continue;
            String iface=props.getInterfaceName();if(iface==null||iface.contains("p2p")||iface.equals(direct.currentInterface()))continue;
            for(LinkAddress link:props.getLinkAddresses()){
                InetAddress address=link.getAddress();if(!(address instanceof Inet4Address)||!LocalEndpoint.isLocal(address)||address.isLoopbackAddress())continue;
                if(result.size()<8&&allowed.add(address))result.add(new AutomaticFileRoutes.Endpoint(address.getHostAddress(),port));
            }
        }
        lanAddresses=Set.copyOf(allowed);return List.copyOf(result);
    }
    private void monitorLan(){
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager==null||networkCallback!=null)return;
        networkCallback=new ConnectivityManager.NetworkCallback(){
            public void onLinkPropertiesChanged(Network network,LinkProperties properties){main.post(AndroidFilePlatform.this::refreshLan);}
            public void onLost(Network network){main.post(AndroidFilePlatform.this::refreshLan);}
        };
        try{manager.registerNetworkCallback(new NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED).removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).addTransportType(NetworkCapabilities.TRANSPORT_WIFI).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build(),networkCallback);}
        catch(RuntimeException unavailable){networkCallback=null;}
    }
    private void refreshLan(){
        ServerSocket listening=server;if(closed||listening==null||listening.isClosed()||endpointUpdates==null)return;
        try{List<AutomaticFileRoutes.Endpoint> endpoints=lanEndpoints(listening.getLocalPort());acceptedStreams.forEach((stream,address)->{if(!lanAddresses.contains(address))closeStream(stream);});endpointUpdates.accept(endpoints);}
        catch(SocketException|RuntimeException unavailable){lanAddresses=Set.of();acceptedStreams.keySet().forEach(AndroidFilePlatform::closeStream);endpointUpdates.accept(List.of());}
    }
    public void connectLan(List<AutomaticFileRoutes.Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed) {
        onMain(()->{if(closed){failed.accept(new IOException("File platform closed"));return;}lanDial.cancelConnect();lanCandidates=List.copyOf(endpoints);lanCandidate=0;lanOpened=opened;lanFailed=failed;nextLan();});
    }
    private void nextLan() {
        if(closed||lanOpened==null)return;
        if(lanCandidate>=lanCandidates.size()){Consumer<Throwable> failure=lanFailed;lanOpened=null;lanFailed=null;failure.accept(new IOException("No ordinary LAN file route"));return;}
        AutomaticFileRoutes.Endpoint endpoint=lanCandidates.get(lanCandidate++);
        try {
            InetAddress host=InetAddress.getByName(endpoint.host);ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network selected=null;int best=-1;
            if(manager!=null)for(Network network:manager.getAllNetworks()){
                NetworkCapabilities caps=manager.getNetworkCapabilities(network);LinkProperties props=manager.getLinkProperties(network);
                if(props==null||props.getInterfaceName()==null||props.getInterfaceName().contains("p2p")||props.getInterfaceName().equals(direct.currentInterface()))continue;
                int score=LanTransport.networkScore(caps,props,host);if(score>0&&score>best){best=score;selected=network;}
            }
            if(selected==null){nextLan();return;}lanDial.connect(host,endpoint.port,selected);
        }catch(IOException|RuntimeException unavailable){nextLan();}
    }
    public void openDirect(boolean dialer,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed) {
        openDirect(UUID.randomUUID().toString(),dialer,opened,failed);
    }
    public void openDirect(String nonce,boolean dialer,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed) {
        onMain(()->{
            if(closed||!directSupported()){failed.accept(new IOException("Wi-Fi Direct file route unavailable"));return;}
            stopDirect();direct.beginFileAttemptDiagnostics(nonce,dialer);discoveryBudget.reset();directOpened=opened;directFailed=failed;directPeerSeen=false;directLastEvent="starting";directLastError="";long ticket=++directAttempt;
            if(!directStarted){directStarted=true;direct.start(localId,nickname);}
            if(ticket!=directAttempt||directOpened==null)return;
            directPeerSeen=direct.currentPeer(remoteId)!=null;
            if(directPeerSeen)discoveryBudget.found();
            direct.armFileAttempt(remoteId,dialer);
            // A warm advertised service does not emit another advertising callback.
            if(direct.advertising()){direct.diagnostics.record(WifiDirectDiagnostics.Event.WARM_ADVERTISEMENT);armDiscoveryPreparation();}
            directTimeout=()->{if(ticket==directAttempt){directLastEvent="timeout";directLastError="timeout";direct.diagnostics.record(WifiDirectDiagnostics.Event.OPEN_TIMEOUT);failDirect(new AutomaticFileRoutes.DirectFailure(AutomaticFileRoutes.DirectFailureReason.OPEN_TIMEOUT));}};main.postDelayed(directTimeout,AutomaticFileRoutes.DIRECT_OPEN_TIMEOUT_MS);
        });
    }
    private void armDiscoveryPreparation(){
        if(directOpened!=null&&!directPeerSeen&&discoveryBudget.prepare(SystemClock.elapsedRealtime()))scheduleDiscoveryTimeout();
    }
    private void scheduleDiscoveryTimeout(){
        clearDiscoveryTimeout();long ticket=directAttempt;
        discoveryTimeout=()->{
            if(ticket!=directAttempt||directOpened==null||directPeerSeen)return;
            String reason=discoveryBudget.failure(SystemClock.elapsedRealtime());if(reason.isEmpty())return;
            directLastEvent=reason;directLastError=reason;
            boolean preparing=reason.equals("discovery_prepare_timeout");
            direct.diagnostics.record(preparing?WifiDirectDiagnostics.Event.PREPARATION_TIMEOUT:WifiDirectDiagnostics.Event.DISCOVERY_TIMEOUT);
            failDirect(new AutomaticFileRoutes.DirectFailure(preparing?AutomaticFileRoutes.DirectFailureReason.DISCOVERY_PREPARE_TIMEOUT:AutomaticFileRoutes.DirectFailureReason.DISCOVERY_TIMEOUT));
        };
        main.postDelayed(discoveryTimeout,Math.max(0,discoveryBudget.expires()-SystemClock.elapsedRealtime()));
    }
    private String directError(UiText reason){return reason.key.equals("directEnableFirst")&&direct.p2pState().equals("disabled")?"p2p_disabled":reason.key;}
    private void failDirect(Throwable reason){Consumer<Throwable> failure=directFailed;if(failure==null)return;direct.diagnostics.record(WifiDirectDiagnostics.Event.LOCAL_FAILURE);stopDirect();failure.accept(reason);}
    private void clearDiscoveryTimeout(){if(discoveryTimeout!=null)main.removeCallbacks(discoveryTimeout);discoveryTimeout=null;}
    private void clearDirectTimeout(){if(directTimeout!=null)main.removeCallbacks(directTimeout);directTimeout=null;}
    public void directPeerFallback(){onMain(()->direct.diagnostics.record(WifiDirectDiagnostics.Event.PEER_FALLBACK));}
    public void stopDirect(){onMain(()->{directAttempt++;preparedGroupNonce=null;discoveryBudget.stop();clearDiscoveryTimeout();clearDirectTimeout();directOpened=null;directFailed=null;direct.diagnostics.stop();if(directGroupMode){directGroupMode=false;directStarted=false;direct.stop();}else{direct.stopSearch();direct.disconnect();}});}
    public void close(){onMain(()->{if(closed)return;closed=true;stopDirect();direct.destroy();lanDial.destroy();acceptedStreams.keySet().forEach(AndroidFilePlatform::closeStream);ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);if(manager!=null&&networkCallback!=null)try{manager.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}networkCallback=null;endpointUpdates=null;try{if(server!=null)server.close();}catch(IOException ignored){}acceptor.shutdownNow();});}
    private void onMain(Runnable action){if(Looper.myLooper()==main.getLooper())action.run();else main.post(action);}
    private static void closeStream(StreamConnection stream){try{stream.close();}catch(IOException ignored){}}
    private abstract static class Events implements TransportListener {
        public void onPeer(Peer peer){}public void onLost(String key){}public void onListening(int mode,UiText detail){}
        public void onConnection(int mode,StreamConnection connection,boolean incoming){closeStream(connection);}
        public void onError(int mode,UiText reason,boolean fatal){}public void onConnectFailed(int mode,UiText reason){}
    }
}
