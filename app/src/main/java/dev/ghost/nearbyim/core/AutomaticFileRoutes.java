package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One authenticated FILE route for an approved chat; chat and engine generations never change. */
public final class AutomaticFileRoutes implements AutoCloseable {
    /** Target discovery after this client's scan ACK, without extending on duplicate callbacks. */
    public static final long DIRECT_DISCOVERY_TIMEOUT_MS=5_000;
    public static final long DIRECT_DISCOVERY_PREPARE_TIMEOUT_MS=5_000;
    /** Total native startup/group/socket budget; identity binding gets a separate deadline. */
    public static final long DIRECT_OPEN_TIMEOUT_MS=50_000;
    private static final long LANE_HANDSHAKE_TIMEOUT_MS=15_000;
    public enum Route { WIFI_DIRECT, LAN, BLUETOOTH }
    public enum DirectFailureReason { DISCOVERY_PREPARE_TIMEOUT, DISCOVERY_TIMEOUT, OPEN_TIMEOUT, FREQUENCY_UNCONFIRMED, BAND_MISMATCH }
    public static final class DirectFailure extends IOException {
        public final DirectFailureReason reason;
        public DirectFailure(DirectFailureReason reason){super(reason.name());this.reason=reason;}
    }
    public static final class Endpoint {
        public final String host;public final int port;
        public Endpoint(String host,int port){
            if(host==null||!host.equals(host.trim())||host.length()>64||port<1||port>65535)throw new IllegalArgumentException("Invalid FILE endpoint");
            LocalEndpoint.parse(host.contains(":")?"["+host+"]:"+port:host+":"+port);this.host=host;this.port=port;
        }
    }
    public static final class Opened {
        public final StreamConnection connection;public final boolean incoming;
        public Opened(StreamConnection connection,boolean incoming){this.connection=Objects.requireNonNull(connection);this.incoming=incoming;}
    }
    public interface Platform {
        boolean directSupported();
        default String directUnavailableReason(){return "unsupported";}
        default String diagnostics(){return "";}
        void prepareLan(Consumer<List<Endpoint>> endpoints,Consumer<StreamConnection> accepted);
        void connectLan(List<Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed);
        void openDirect(boolean dialer,Consumer<Opened> opened,Consumer<Throwable> failed);
        default void openDirect(String attempt,boolean dialer,Consumer<Opened> opened,Consumer<Throwable> failed){openDirect(dialer,opened,failed);}
        default void directPeerFallback(){}
        default boolean directGroupsSupported(){return false;}
        default boolean direct5GhzSupported(){return false;}
        default void prepareDirectGroup(String attempt,boolean owner){}
        default void createDirectGroup(String attempt,boolean prefer5Ghz,Consumer<DirectGroupCredentials> advertised,Consumer<Opened> opened,Consumer<Throwable> failed){failed.accept(new IOException("Explicit P2P groups unavailable"));}
        /** A working parent LAN must not be replaced by an unmeasured 2.4GHz group. */
        default void createDirectGroup(String attempt,boolean prefer5Ghz,boolean allowAutoFallback,Consumer<DirectGroupCredentials> advertised,Consumer<Opened> opened,Consumer<Throwable> failed){createDirectGroup(attempt,prefer5Ghz,advertised,opened,failed);}
        default void joinDirectGroup(String attempt,DirectGroupCredentials group,Consumer<Opened> opened,Consumer<Throwable> failed){failed.accept(new IOException("Explicit P2P groups unavailable"));}
        default List<Endpoint> currentLanEndpoints(){return null;}
        void stopDirect();void close();
    }
    public interface Listener {void receive(TransferPacket packet);default void receive(TransferPacket packet,BooleanSupplier current){if(current.getAsBoolean())receive(packet);}void changed();void failed(IOException error);default boolean busy(){return false;}}
    private final FramedSession chat;private final String localId,nickname,remoteId,remoteKey,chatRoute;
    private final DeviceIdentity identity;private final byte[] noiseKey;private final BooleanSupplier approved;private final Platform platform;private final Listener listener;
    private final boolean lead;
    private final ScheduledThreadPoolExecutor actor=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"nearby-file-routes");t.setDaemon(true);return t;});
    private final Semaphore pending=new Semaphore(128);
    private final Set<StreamConnection> pendingConnections=ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<Void>> laneTransitions=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;private volatile boolean terminal;private boolean started,dialed,bound,selecting,endpointsPrepared,phaseFailed;
    private volatile FramedSession selected;private volatile FramedSession candidate;
    private volatile String selectedRoute="";private volatile String generation,nonce;private volatile Route attempting;
    private List<Endpoint> localEndpoints=Collections.emptyList();private ScheduledFuture<?> deadline;
    private volatile CompletableFuture<Route> ready;
    private volatile String directOutcome="not_attempted",directSkipReason="",lastRouteFailure="";
    private volatile String pathProbe;private String verifiedPathNonce;private volatile boolean parentHotspot;private ScheduledFuture<?> pathDeadline;private SocketPath pathSnapshot;
    private volatile String groupProbe,directMethod="";private ScheduledFuture<?> groupProbeDeadline;
    private boolean groupAttempt,prefer5Ghz;private String groupGrant;private DirectGroupCredentials groupParameters;
    private final Set<String> retiredGroups=new LinkedHashSet<>();
    private long retiredParentBytes;private int retiredParentControls;
    private volatile boolean refreshing;private String refreshRequest,recheckNonce;private ScheduledFuture<?> recheckDeadline;
    private final FileDataPaths dataPaths=new FileDataPaths();
    private volatile ClientAttachmentTransfers attachedFiles;
    private volatile FileRouteHandover handover;
    private FileRoutePacket pendingLinkCapability;
    private volatile boolean parentRecovery,parentSecurityFailure;
    private volatile FramedSession retiredParentFile;
    private final CompletableFuture<Void> parentFinished=new CompletableFuture<>();
    public AutomaticFileRoutes(FramedSession chat,String localId,String nickname,DeviceIdentity identity,byte[] noiseKey,String remoteId,String remoteKey,String chatRoute,BooleanSupplier approved,Platform platform,Listener listener){
        this.chat=Objects.requireNonNull(chat);this.localId=Objects.requireNonNull(localId).toLowerCase(Locale.ROOT);this.nickname=nickname;this.identity=Objects.requireNonNull(identity);this.noiseKey=noiseKey.clone();this.remoteId=Objects.requireNonNull(remoteId).toLowerCase(Locale.ROOT);this.remoteKey=Objects.requireNonNull(remoteKey);this.chatRoute=Objects.requireNonNull(chatRoute);this.approved=Objects.requireNonNull(approved);this.platform=Objects.requireNonNull(platform);this.listener=Objects.requireNonNull(listener);lead=this.localId.compareTo(this.remoteId)<0;actor.setRemoveOnCancelPolicy(true);
    }
    private void post(Runnable action){
        if(closed)return;
        if(!pending.tryAcquire()){close();listener.failed(new IOException("FILE control queue full"));return;}
        try{actor.execute(()->{try{if(!closed)action.run();}catch(RuntimeException e){fail(new IOException("FILE routing failed",e),true);}finally{pending.release();}});}catch(RejectedExecutionException ignored){pending.release();}
    }
    private boolean valid(){return !closed&&!terminal&&(chat.isReady()||parentRecovery)&&approved.getAsBoolean()&&(parentRecovery||Objects.equals(generation,chat.connectionGeneration()));}
    public void start(){post(this::startActor);}
    public void attachFiles(ClientAttachmentTransfers files){
        try{if(files==null||!files.matches(identity.fingerprint(),DeviceIdentity.fingerprint(remoteKey),chat.connectionGeneration()))throw new IllegalArgumentException("FILE engine identity mismatch");}
        catch(java.security.GeneralSecurityException invalid){throw new IllegalArgumentException("Invalid FILE peer root",invalid);}
        if(attachedFiles!=null&&attachedFiles!=files)throw new IllegalStateException("FILE engine already attached");
        attachedFiles=files;post(()->{startActor();startHandover();});
    }
    private void startHandover(){
        if(handover!=null||attachedFiles==null||!valid()||!chat.automaticFilesSupported())return;
        handover=new FileRouteHandover(new FileRouteHandover.Host(){
            public boolean valid(){return AutomaticFileRoutes.this.valid();}public boolean lead(){return lead;}
            public boolean recoverParent(){return parentClosed();}public boolean parentLost(){return parentRecovery;}public void parentFinished(){parentFinished.complete(null);}
            public String generation(){return generation;}public String routeToken(){return nonce;}public String parentRoute(){return chatRoute;}public String route(){return selectedRoute;}
            public FramedSession parent(){return chat;}public FramedSession selected(){return selected;}public ClientAttachmentTransfers files(){return attachedFiles;}
            public ScheduledExecutorService timer(){return actor;}public void post(Runnable work){AutomaticFileRoutes.this.post(work);}public void changed(){listener.changed();}
            public List<Endpoint> lanEndpoints(){List<Endpoint> current=platform.currentLanEndpoints();return current!=null?current:endpointsPrepared?localEndpoints:null;}
            public void dial(List<Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed){platform.connectLan(endpoints,opened,failed);}
            public FramedSession lane(StreamConnection connection,boolean initiator,FramedSession.Listener listener)throws IOException{FramedSession wire=FramedSession.secure(connection,localId,nickname,identity,noiseKey,initiator,listener,false,true);wire.restrictToFileLane();return wire;}
            public boolean expectedPeer(FramedSession wire,Frame hello){return remoteId.equals(hello.id)&&remoteKey.equals(wire.remotePublicKey());}
            public void install(FramedSession wire,String token,boolean fault,String route){installHandover(wire,token,fault,route);}
            public void transfer(FramedSession source,String token,TransferPacket packet){deliver(packet,source,selectedRoute,token);}public void failure(IOException error,boolean security){fail(error,security);}
        });
        if(pendingLinkCapability!=null){handover.capability(pendingLinkCapability);pendingLinkCapability=null;}
    }
    private void installHandover(FramedSession wire,String token,boolean fault,String route){
        FramedSession old=selected;String before=selectedRoute;String previous=nonce;
        retireParent();retireGroup(previous);retireGroup(groupGrant);clearGroupProbe();groupGrant=null;groupParameters=null;cancelDeadline();
        candidate=wire==chat?null:wire;selected=wire;selectedRoute=route;nonce=token;attempting=parseRoute(route);bound=true;dialed=true;selecting=false;refreshing=false;
        if(before.equals("wifi-direct")){directOutcome=fault?"interrupted":"slower_than_lan";lastRouteFailure=fault?"direct_interrupted":"";}
        if(old!=null&&old!=wire&&old!=chat)old.close(UiText.EMPTY);platform.stopDirect();listener.changed();
    }
    public void parentFailure(IOException error){if(!(error instanceof java.net.SocketException)&&!(error instanceof EOFException)&&!(error instanceof InterruptedIOException))parentSecurityFailure=true;}
    /** Retain only the existing approved transfer scope; no new chat or app-restart consent. */
    public boolean parentClosed(){
        FileRouteHandover recovery=handover;ClientAttachmentTransfers files=attachedFiles;
        if(parentRecovery)return true;
        if(closed||terminal||parentSecurityFailure||recovery==null||!recovery.supported()||files==null||!files.automaticActive()&&!recovery.busy()||!approved.getAsBoolean())return false;
        retiredParentFile=selected;parentRecovery=true;post(recovery::parentInterrupted);return true;
    }
    public CompletableFuture<Void> parentRecoveryFinished(){return parentFinished;}
    public void receiveFileLink(FileLinkPacket packet){post(()->receiveFileLink(chat,packet));}
    private void receiveFileLink(FramedSession wire,FileLinkPacket packet){startActor();startHandover();if(!valid())return;if(handover==null){fail(new IOException("FILE migration without attached engine"),true);return;}handover.receive(packet,wire);}
    public boolean sendRecoveryOffer(TransferPacket packet){FileRouteHandover current=handover;return current!=null&&current.sendRecoveryOffer(packet);}
    private void startActor(){
        if(started)return;started=true;generation=chat.connectionGeneration();
        if(!valid()){fail(new IOException("Approved chat unavailable"),true);return;}
        platform.prepareLan(endpoints->post(()->{if(endpoints==null||endpoints.size()>8){fail(new IOException("Too many FILE endpoints"),true);return;}endpointsPrepared=true;localEndpoints=Collections.unmodifiableList(new ArrayList<>(endpoints));if(handover!=null)handover.lanPrepared();if(attempting==Route.LAN)sendEndpoints();}),connection->postAccepted(connection));
        actor.scheduleWithFixedDelay(()->{if(!closed&&!terminal&&!valid()&&!parentClosed())fail(new IOException("Chat closed or approval revoked"),true);},1,1,TimeUnit.SECONDS);
        startHandover();
    }
    public synchronized CompletableFuture<Route> ensureReady(){
        if(closed||terminal||!chat.isReady()||!approved.getAsBoolean())return failedFuture(new IOException("Approved FILE route unavailable"));
        if(handover!=null&&handover.busy())return handover.ready();
        if(selected!=null&&!refreshing)return CompletableFuture.completedFuture(selectedKind());
        if(ready==null||ready.isDone()){ready=new CompletableFuture<>();post(()->{startActor();if(!valid())return;if(!chat.automaticFilesSupported()){selectLegacy();return;}if(lead){if(attempting==null)chooseInitialRoute();}else if(attempting==null)control(FileRoutePacket.Kind.REQUEST,UUID.randomUUID().toString(),Route.LAN,false,Collections.emptyList());});}
        return ready;
    }
    /** Re-evaluate a prior fallback only when both engines are idle. Call before enqueueing a new batch. */
    public synchronized CompletableFuture<Route> prepareNewBatch(){
        if(selected==null||closed||terminal)return ensureReady();
        if(!valid())return failedFuture(new IOException("Approved FILE route unavailable"));
        if(dedicatedDirect()||!chat.automaticFilesSupported()||listener.busy())return CompletableFuture.completedFuture(selectedKind());
        if(refreshing)return ready;
        refreshing=true;ready=new CompletableFuture<>();post(()->{
            if(lead)requestRecheck();else{refreshRequest=UUID.randomUUID().toString();control(FileRoutePacket.Kind.REFRESH_REQUEST,refreshRequest,parseRoute(selectedRoute),false,Collections.emptyList());watchRecheck();}
        });return ready;
    }
    private void watchRecheck(){
        if(recheckDeadline!=null)recheckDeadline.cancel(false);
        recheckDeadline=actor.schedule(()->{if(refreshing&&selected!=null)finishRecheck();},8,TimeUnit.SECONDS);
    }
    private void finishRecheck(){
        CompletableFuture<Route> finishing;Route route;
        synchronized(this){finishing=ready;route=selected==null?null:selectedKind();refreshing=false;}
        refreshRequest=null;recheckNonce=null;if(recheckDeadline!=null){recheckDeadline.cancel(false);recheckDeadline=null;}
        if(route!=null){if(finishing!=null&&!finishing.isDone())finishing.complete(route);listener.changed();}
    }
    private void requestRecheck(){
        if(selected==null||listener.busy()||dedicatedDirect()){finishRecheck();return;}
        if(recheckNonce!=null)return;
        refreshing=true;recheckNonce=UUID.randomUUID().toString();control(FileRoutePacket.Kind.RECHECK,recheckNonce,parseRoute(selectedRoute),false,Collections.emptyList());watchRecheck();
    }
    private boolean receiveRecheck(FileRoutePacket p){
        switch(p.kind){
            case REFRESH_REQUEST:
                if(!lead)return true;
                if(selected==null)return true;
                if(listener.busy()||dedicatedDirect())control(FileRoutePacket.Kind.BUSY,p.nonce,parseRoute(selectedRoute),false,Collections.emptyList());else requestRecheck();return true;
            case RECHECK:
                if(lead){fail(new IOException("Invalid FILE recheck coordinator"),true);return true;}
                if(selected==null||listener.busy()){control(FileRoutePacket.Kind.BUSY,p.nonce,p.route,false,Collections.emptyList());return true;}
                refreshing=true;recheckNonce=p.nonce;watchRecheck();control(FileRoutePacket.Kind.RECHECK_ACK,p.nonce,p.route,false,Collections.emptyList());return true;
            case RECHECK_ACK:
                if(!lead||!Objects.equals(recheckNonce,p.nonce))return true;
                if(listener.busy()){control(FileRoutePacket.Kind.BUSY,p.nonce,p.route,false,Collections.emptyList());finishRecheck();return true;}
                synchronized(this){retireParent();selected=null;selectedRoute="";}finishRecheck();chooseInitialRoute();return true;
            case BUSY:
                if(Objects.equals(recheckNonce,p.nonce)||Objects.equals(refreshRequest,p.nonce))finishRecheck();return true;
            default:return false;
        }
    }
    private static <T> CompletableFuture<T> failedFuture(IOException e){CompletableFuture<T> f=new CompletableFuture<>();f.completeExceptionally(e);return f;}
    private void selectLegacy(){directOutcome="peer_legacy";selected=chat;selectedRoute=chatRoute;complete(parseRoute(chatRoute));}
    private void chooseInitialRoute(){
        if(pathProbe!=null)return;
        resetCandidate();attempting=null;parentHotspot=false;verifiedPathNonce=null;
        SocketPath path="lan".equals(chatRoute)?chat.socketPath():null;
        if(!valid())return;
        if(path==null){beginInitial();return;}
        // ENDPOINTS with a version-3 UUID is a backwards-compatible path advertisement.
        // Legacy attempts use random version-4 UUIDs, so older peers safely ignore it.
        String token=UUID.nameUUIDFromBytes((generation+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();
        pathSnapshot=path;pathProbe=token;listener.changed();pathDeadline=actor.schedule(()->{if(Objects.equals(pathProbe,token)&&valid()){pathProbe=null;pathSnapshot=null;beginInitial();}},3,TimeUnit.SECONDS);
        if(!control(FileRoutePacket.Kind.ENDPOINTS,token,Route.LAN,false,pathEndpoints(path)))fail(new IOException("FILE path probe unavailable"),false);
    }
    private boolean receivePath(FileRoutePacket p){
        if(p.kind!=FileRoutePacket.Kind.ENDPOINTS||UUID.fromString(p.nonce).version()!=3)return false;
        if(!"lan".equals(chatRoute)||p.route!=Route.LAN)return true;
        if(lead){
            if(!Objects.equals(pathProbe,p.nonce))return true;
            SocketPath path=pathSnapshot;parentHotspot=path!=null&&path.confirmsHotspot(decodePath(p.endpoints));verifiedPathNonce=parentHotspot?p.nonce:null;
            pathProbe=null;pathSnapshot=null;if(pathDeadline!=null){pathDeadline.cancel(false);pathDeadline=null;}beginInitial();
        }else{
            if(selected!=null&&!refreshing)return true;
            SocketPath path=chat.socketPath();if(!valid())return true;parentHotspot=path!=null&&path.confirmsHotspot(decodePath(p.endpoints));verifiedPathNonce=parentHotspot?p.nonce:null;
            control(FileRoutePacket.Kind.ENDPOINTS,p.nonce,Route.LAN,false,path==null?Collections.emptyList():pathEndpoints(path));
        }
        return true;
    }
    private static List<Endpoint> pathEndpoints(SocketPath path){
        Endpoint local=new Endpoint(path.local.getAddress().getHostAddress(),path.local.getPort()),peer=new Endpoint(path.peer.getAddress().getHostAddress(),path.peer.getPort());
        return path.peerIsWifiGateway?List.of(local,peer,peer):List.of(local,peer);
    }
    private static SocketPath decodePath(List<Endpoint> endpoints){
        if(endpoints.size()!=2&&endpoints.size()!=3)return null;
        Endpoint local=endpoints.get(0),peer=endpoints.get(1);
        if(endpoints.size()==3&&(!peer.host.equals(endpoints.get(2).host)||peer.port!=endpoints.get(2).port))return null;
        return new SocketPath(local.host,local.port,peer.host,peer.port,endpoints.size()==3);
    }
    private Route initialRoute(){
        lastRouteFailure="";directSkipReason="";
        if(parentHotspot){directOutcome="not_needed_existing_hotspot";return Route.LAN;}
        if(platform.directSupported()){directOutcome="not_attempted";return Route.WIFI_DIRECT;}
        directOutcome="skipped";directSkipReason=platform.directUnavailableReason();return Route.LAN;
    }
    private Route selectedKind(){return selected==chat?parseRoute(chatRoute):parseRoute(selectedRoute);}
    private String challenge(){return UUID.nameUUIDFromBytes((generation+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();}
    private void clearGroupProbe(){groupProbe=null;if(groupProbeDeadline!=null){groupProbeDeadline.cancel(false);groupProbeDeadline=null;}}
    private void beginInitial(){
        Route route=initialRoute();groupAttempt=false;
        if(route!=Route.WIFI_DIRECT||!platform.directGroupsSupported()){begin(route);return;}
        String token=challenge();groupProbe=token;listener.changed();
        groupProbeDeadline=actor.schedule(()->{if(Objects.equals(groupProbe,token)&&valid()){clearGroupProbe();begin(Route.WIFI_DIRECT);}},1500,TimeUnit.MILLISECONDS);
        // Older receivers already ignore version-3 ENDPOINTS on a non-LAN route.
        if(!control(FileRoutePacket.Kind.ENDPOINTS,token,Route.WIFI_DIRECT,false,List.of()))fail(new IOException("Direct capability probe unavailable"),false);
    }
    private boolean receiveGroupProbe(FileRoutePacket packet){
        if(packet.kind==FileRoutePacket.Kind.ENDPOINTS&&packet.route==Route.WIFI_DIRECT&&UUID.fromString(packet.nonce).version()==3){
            if(!lead&&packet.endpoints.isEmpty()&&(selected==null&&attempting==null||refreshing)&&!retiredGroups.contains(packet.nonce)&&!listener.busy()&&platform.directSupported()&&platform.directGroupsSupported()){
                groupGrant=packet.nonce;
                if(!chat.sendFileRoute(FileRoutePacket.directCapabilities(packet.nonce,generation,platform.direct5GhzSupported())))fail(new IOException("Direct capability reply unavailable"),false);
            }
            return true;
        }
        if(packet.kind==FileRoutePacket.Kind.DIRECT_CAPABILITIES){
            if(lead&&groupProbe!=null&&Objects.equals(groupProbe,packet.nonce)&&attempting==null){
                groupGrant=packet.nonce;clearGroupProbe();groupAttempt=true;prefer5Ghz=packet.supports5Ghz&&platform.direct5GhzSupported();
                if("lan".equals(chatRoute)&&!prefer5Ghz){directOutcome="skipped";directSkipReason="5ghz_unavailable";begin(Route.LAN);}
                else begin(Route.WIFI_DIRECT);
            }
            return true;
        }
        return false;
    }
    private boolean dedicatedDirect(){return selected!=null&&selected!=chat&&"wifi-direct".equals(selectedRoute);}
    private static Route parseRoute(String route){return "wifi-direct".equals(route)?Route.WIFI_DIRECT:"lan".equals(route)?Route.LAN:Route.BLUETOOTH;}
    private static String name(Route r){return r==Route.WIFI_DIRECT?"wifi-direct":r==Route.LAN?"lan":"bluetooth";}
    private boolean control(FileRoutePacket.Kind kind,String token,Route route,boolean terminal,List<Endpoint> endpoints){return chat.sendFileRoute(new FileRoutePacket(kind,token,generation,route,terminal,endpoints));}
    private void begin(Route route){
        if(route!=Route.WIFI_DIRECT)groupAttempt=false;
        String nextNonce=parentHotspot&&route==Route.LAN?verifiedPathNonce:groupAttempt?Objects.requireNonNull(groupGrant):UUID.randomUUID().toString();
        resetCandidate();nonce=nextNonce;attempting=route;
        if(!control(FileRoutePacket.Kind.BEGIN,nonce,route,false,Collections.emptyList())){fail(new IOException("FILE control unavailable"),true);return;}enter();
    }
    private void enter(){
        phaseFailed=false;
        if(!valid()){fail(new IOException("Approval revoked"),true);return;}
        String token=nonce;listener.changed();
        long timeout=attempting==Route.WIFI_DIRECT?DIRECT_OPEN_TIMEOUT_MS+LANE_HANDSHAKE_TIMEOUT_MS:attempting==Route.LAN?8_000:15_000;
        deadline=actor.schedule(()->{if(Objects.equals(token,nonce)&&selected==null)attemptFailed(new IOException("FILE route timed out"),"timeout");},timeout,TimeUnit.MILLISECONDS);
        if(attempting==Route.WIFI_DIRECT){
            parentHotspot=false;verifiedPathNonce=null;
            directOutcome="attempting";directSkipReason="";
            if(!platform.directSupported()){directOutcome="skipped";directSkipReason=platform.directUnavailableReason();attemptFailed(new IOException("Direct unsupported"),"unavailable");return;}
            directMethod=groupAttempt?"known_group":"dns_sd";
            if(groupAttempt){
                if(!platform.directGroupsSupported()){attemptFailed(new IOException("Known-group capability unavailable"),"unavailable");return;}
                platform.prepareDirectGroup(token,lead);
                if(lead)platform.createDirectGroup(token,prefer5Ghz,!"lan".equals(chatRoute),parameters->post(()->{
                    if(!current(token,Route.WIFI_DIRECT)||!groupAttempt)return;
                    if(groupParameters!=null){if(!groupParameters.equals(parameters))attemptFailed(new IOException("Local Direct group changed"));return;}
                    if(!acceptGroupBand(parameters))return;
                    groupParameters=parameters;if(!chat.sendFileRoute(FileRoutePacket.directGroup(token,generation,parameters)))fail(new IOException("Direct group control unavailable"),false);
                }),opened->opened(opened,token,Route.WIFI_DIRECT),error->post(()->{if(current(token,Route.WIFI_DIRECT))attemptFailed(asIo(error));}));
            }else platform.openDirect(token,lead,opened->opened(opened,token,Route.WIFI_DIRECT),error->post(()->{if(current(token,Route.WIFI_DIRECT))attemptFailed(asIo(error));}));
        }else if(attempting==Route.LAN){
            if("lan".equals(chatRoute)){if(lead)select();}
            else sendEndpoints();
        }else if(lead)select();
    }
    private boolean current(String token,Route route){return valid()&&!phaseFailed&&Objects.equals(nonce,token)&&attempting==route&&selected==null;}
    private void sendEndpoints(){
        List<Endpoint> current=platform.currentLanEndpoints();
        if(current!=null){if(current.size()>8){fail(new IOException("Invalid current LAN endpoints"),true);return;}List<Endpoint> snapshot=new ArrayList<>(current);if(snapshot.contains(null)){fail(new IOException("Invalid current LAN endpoints"),true);return;}localEndpoints=Collections.unmodifiableList(snapshot);endpointsPrepared=true;}
        if(endpointsPrepared&&nonce!=null)control(FileRoutePacket.Kind.ENDPOINTS,nonce,Route.LAN,false,localEndpoints);
    }
    public void receiveControl(FileRoutePacket packet){post(()->{if(extra()&&packet!=null&&(packet.kind==FileRoutePacket.Kind.SELECT||packet.kind==FileRoutePacket.Kind.SELECT_ACK)){fail(new IOException("FILE selection on wrong lane"),true);return;}receive(packet);});}
    private void receive(FileRoutePacket p){
        startActor();if(!valid())return;
        if(p==null||!generation.equals(p.generation)){fail(new IOException("FILE parent binding mismatch"),true);return;}
        if(p.kind==FileRoutePacket.Kind.ENDPOINTS&&p.route==Route.BLUETOOTH&&UUID.fromString(p.nonce).version()==3){startHandover();if(handover!=null)handover.capability(p);else if(!lead)pendingLinkCapability=p;return;}
        if(receiveGroupProbe(p))return;
        if(receivePath(p))return;
        if(receiveRecheck(p))return;
        if(p.kind==FileRoutePacket.Kind.REQUEST){if(lead&&selected==null&&attempting==null)chooseInitialRoute();return;}
        if(p.kind==FileRoutePacket.Kind.BEGIN){
            if(lead){fail(new IOException("Invalid FILE coordinator"),true);return;}
            boolean knownGroup=p.route==Route.WIFI_DIRECT&&UUID.fromString(p.nonce).version()==3;
            if(knownGroup&&(retiredGroups.contains(p.nonce)||groupAttempt&&Objects.equals(nonce,p.nonce)))return;
            if(selected!=null&&!refreshing)return;
            Route previous=refreshing?null:attempting;
            boolean ordered=p.route==Route.WIFI_DIRECT?previous==null:p.route==Route.LAN?previous==null||previous==Route.WIFI_DIRECT:previous==Route.LAN&&"bluetooth".equals(chatRoute);
            if(!ordered){fail(new IOException("Invalid FILE fallback provenance or order"),true);return;}
            if(knownGroup&&(!Objects.equals(groupGrant,p.nonce)||!platform.directGroupsSupported())){fail(new IOException("Unnegotiated Direct group"),true);return;}
            parentHotspot=p.route==Route.LAN&&parentHotspot&&Objects.equals(verifiedPathNonce,p.nonce);
            if(previous==null){lastRouteFailure="";directSkipReason="";directOutcome=parentHotspot?"not_needed_existing_hotspot":p.route==Route.WIFI_DIRECT?"not_attempted":"peer_skipped";}
            else if(previous==Route.WIFI_DIRECT&&p.route==Route.LAN&&directOutcome.equals("attempting")){directOutcome="failed";lastRouteFailure="peer_unavailable";platform.directPeerFallback();}
            if(refreshing){synchronized(this){retireParent();selected=null;selectedRoute="";}finishRecheck();}resetCandidate();groupAttempt=knownGroup;nonce=p.nonce;attempting=p.route;enter();return;
        }
        if(!Objects.equals(nonce,p.nonce)||p.route!=attempting)return;
        switch(p.kind){
            case DIRECT_GROUP:
                if(phaseFailed)return;
                if(lead||!groupAttempt||attempting!=Route.WIFI_DIRECT){fail(new IOException("Unexpected Direct credentials"),true);return;}
                if(groupParameters!=null){if(!groupParameters.equals(p.directGroup))fail(new IOException("Conflicting Direct credentials"),true);return;}
                if(!acceptGroupBand(p.directGroup))return;
                groupParameters=p.directGroup;String groupToken=nonce;
                platform.joinDirectGroup(groupToken,p.directGroup,opened->opened(opened,groupToken,Route.WIFI_DIRECT),error->post(()->{if(current(groupToken,Route.WIFI_DIRECT))attemptFailed(asIo(error));}));break;
            case ENDPOINTS:
                if(lead&&attempting==Route.LAN&&!"lan".equals(chatRoute)&&!dialed){
                    dialed=true;if(p.endpoints.isEmpty()){attemptFailed(new IOException("No LAN endpoints"));return;}
                    String token=nonce;
                    platform.connectLan(p.endpoints,connection->opened(new Opened(connection,false),token,Route.LAN),error->post(()->{if(current(token,Route.LAN))attemptFailed(asIo(error));}));
                }break;
            case ABORT:fail(new IOException("Peer canceled FILE route"),false);break;
            case FAIL:if(p.terminal)fail(new IOException("Peer rejected FILE identity binding"),true);else if(lead){if(attempting==Route.WIFI_DIRECT)platform.directPeerFallback();attemptFailed(new IOException("Peer FILE route unavailable"),"peer_unavailable");}break;
            case SELECT:
                if(lead){fail(new IOException("Invalid FILE selector"),true);return;}
                if(extra()&&!bound){fail(new IOException("Unbound FILE selection"),true);return;}
                selected=extra()?candidate:chat;selectedRoute=selected==chat?(parentHotspot?"wifi-direct":chatRoute):name(attempting);if(selected==chat)clearParentDrain();cancelDeadline();selection(FileRoutePacket.Kind.SELECT_ACK);complete(attempting);break;
            case SELECT_ACK:if(lead&&selecting){selected=extra()?candidate:chat;selectedRoute=selected==chat?(parentHotspot?"wifi-direct":chatRoute):name(attempting);if(selected==chat)clearParentDrain();cancelDeadline();complete(attempting);}break;
            default:fail(new IOException("Unexpected parent FILE control"),true);
        }
    }
    private boolean acceptGroupBand(DirectGroupCredentials group){
        if(!"lan".equals(chatRoute)||group.frequency>=4900&&group.frequency<5925)return true;
        attemptFailed(new DirectFailure(group.frequency==0?DirectFailureReason.FREQUENCY_UNCONFIRMED:DirectFailureReason.BAND_MISMATCH));return false;
    }
    private boolean extra(){return attempting==Route.WIFI_DIRECT||attempting==Route.LAN&&!"lan".equals(chatRoute);}
    private boolean selection(FileRoutePacket.Kind kind){FileRoutePacket p=new FileRoutePacket(kind,nonce,generation,attempting,false,Collections.emptyList());return extra()?candidate!=null&&candidate.sendFileRoute(p):chat.sendFileRoute(p);}
    private void select(){selecting=true;if(!selection(FileRoutePacket.Kind.SELECT))fail(new IOException("FILE selection failed"),true);}
    private void postAccepted(StreamConnection connection){
        pendingConnections.add(connection);if(closed){pendingConnections.remove(connection);dispose(connection);return;}
        post(()->{pendingConnections.remove(connection);if(handover!=null&&handover.accept(connection))return;String token=nonce;opened(new Opened(connection,true),token,Route.LAN);});
    }
    private void opened(Opened opened,String token,Route route){
        pendingConnections.add(opened.connection);
        if(closed){pendingConnections.remove(opened.connection);dispose(opened.connection);return;}
        post(()->{pendingConnections.remove(opened.connection);if(!current(token,route)||candidate!=null){dispose(opened.connection);return;}
            try{
                Lane listener=new Lane(token,route,!opened.incoming);
                candidate=FramedSession.secure(opened.connection,localId,nickname,identity,noiseKey,!opened.incoming,listener,false,true);listener.wire=candidate;candidate.restrictToFileLane();
                if(handover!=null&&handover.supported())candidate.enableFileLink();
                cancelDeadline();deadline=actor.schedule(()->{if(current(token,route))attemptFailed(new IOException("FILE lane handshake timed out"),"handshake_timeout");},LANE_HANDSHAKE_TIMEOUT_MS,TimeUnit.MILLISECONDS);candidate.start();
            }catch(IOException e){dispose(opened.connection);attemptFailed(e);}
        });
    }
    private final class Lane implements FramedSession.Listener {
        final String token;final Route route;final boolean initiator;FramedSession wire;
        Lane(String token,Route route,boolean initiator){this.token=token;this.route=route;this.initiator=initiator;}
        boolean live(){return current(token,route)&&candidate==wire||valid()&&selected==wire&&Objects.equals(token,nonce);}
        public void onHello(Frame hello){post(()->{if(!live()){wire.close(UiText.EMPTY);return;}if(!remoteId.equals(hello.id)||!remoteKey.equals(wire.remotePublicKey())){fail(new IOException("FILE peer identity mismatch"),true);return;}wire.approve();});}
        public void onReady(){post(()->{if(!live())return;if(!wire.automaticFilesSupported()){attemptFailed(new IOException("FILE lane capability unavailable"));return;}if(initiator)wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BIND,token,generation,route,false,Collections.emptyList()));});}
        public void onFileRoute(FileRoutePacket packet){CompletableFuture<Void> processed=new CompletableFuture<>();laneTransitions.add(processed);if(closed){laneTransitions.remove(processed);return;}post(()->{try{
            if(!live())return;
            if(!generation.equals(packet.generation)||!token.equals(packet.nonce)||route!=packet.route){fail(new IOException("FILE lane binding mismatch"),true);return;}
            if(bound&&(packet.kind==FileRoutePacket.Kind.SELECT||packet.kind==FileRoutePacket.Kind.SELECT_ACK)){receive(packet);return;}
            if(packet.kind==FileRoutePacket.Kind.BIND&&!initiator&&!bound){bound=true;wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.BIND_ACK,token,generation,route,false,Collections.emptyList()));if(lead)select();}
            else if(packet.kind==FileRoutePacket.Kind.BIND_ACK&&initiator&&!bound){bound=true;if(lead)select();}
            else fail(new IOException("Unexpected FILE lane binding"),true);
        }finally{processed.complete(null);}});try{processed.get(15,TimeUnit.SECONDS);}catch(Exception error){wire.close(UiText.EMPTY);}finally{laneTransitions.remove(processed);}}
        public void onTransfer(TransferPacket packet){
            if(!live())return;
            if(selected==wire&&generation.equals(packet.connectionGeneration))deliver(packet,wire,route==Route.WIFI_DIRECT?"wifi-direct":route==Route.LAN?"lan":"bluetooth",token);
            else post(()->{if(live())fail(new IOException("FILE packet before selection or wrong generation"),true);});
        }
        public void onFileLink(FileLinkPacket packet){post(()->{if(live())receiveFileLink(wire,packet);});}
        public void onText(Frame f){post(()->{if(live())fail(new IOException("Text on FILE lane"),true);});}public void onAck(String id){post(()->{if(live())fail(new IOException("Receipt on FILE lane"),true);});}
        public void onFailure(IOException e){post(()->{if(live()&&(e instanceof NoiseRecordChannel.AuthenticationException))fail(e,true);});}
        public void onClosed(UiText reason){post(()->{if(!live()||refreshing)return;if(selected==wire){if(handover!=null&&handover.interrupted())return;fail(new IOException("FILE lane interrupted"),false);}else attemptFailed(new IOException("FILE lane handshake failed"),"handshake_failed");});}
    }
    private void attemptFailed(IOException error){attemptFailed(error,error instanceof DirectFailure?((DirectFailure)error).reason.name().toLowerCase(Locale.ROOT):"platform_error");}
    private void attemptFailed(IOException error,String reason){
        if(!valid()||selected!=null)return;
        lastRouteFailure=reason;if(attempting==Route.WIFI_DIRECT&&!directOutcome.equals("skipped"))directOutcome="failed";
        if(!lead){control(FileRoutePacket.Kind.FAIL,nonce,attempting,false,Collections.emptyList());resetCandidate();phaseFailed=true;return;}
        Route next=attempting==Route.WIFI_DIRECT?Route.LAN:Route.BLUETOOTH;
        if(attempting==Route.BLUETOOTH){fail(error,false);return;}begin(next);
    }
    private void complete(Route route){if(route==Route.WIFI_DIRECT)directOutcome="selected";if(handover!=null)handover.selected();synchronized(this){if(ready!=null&&!ready.isDone())ready.complete(route);}listener.changed();}
    private void fail(IOException error,boolean security){
        CompletableFuture<Route> retiring;boolean notifyPeer;
        synchronized(this){
            if(closed)return;
            pathProbe=null;pathSnapshot=null;parentHotspot=false;verifiedPathNonce=null;if(pathDeadline!=null){pathDeadline.cancel(false);pathDeadline=null;}
            lastRouteFailure=security?"security_error":"route_interrupted";
            if(attempting==Route.WIFI_DIRECT&&directOutcome.equals("attempting"))directOutcome="failed";
            notifyPeer=security&&!terminal&&nonce!=null&&generation!=null&&chat.isReady();
            if(!security)retireParent();else{retiredParentBytes=0;retiredParentControls=0;}
            if(parentRecovery)parentFinished.completeExceptionally(error);terminal|=security;retiring=ready;ready=null;selected=null;selectedRoute="";refreshing=false;
        }
        if(notifyPeer)control(FileRoutePacket.Kind.FAIL,nonce,attempting,true,Collections.emptyList());
        if(handover!=null)handover.stop(error);
        finishRecheck();resetCandidate();attempting=null;nonce=null;
        if(security)platform.close();if(retiring!=null&&!retiring.isDone())retiring.completeExceptionally(error);listener.failed(error);listener.changed();
    }
    private void cancelDeadline(){if(deadline!=null){deadline.cancel(false);deadline=null;}}
    private void retireGroup(String token){if(token==null)return;retiredGroups.add(token);if(retiredGroups.size()>128)retiredGroups.remove(retiredGroups.iterator().next());}
    private void resetCandidate(){
        cancelDeadline();retireGroup(groupProbe);retireGroup(groupGrant);clearGroupProbe();groupGrant=null;groupParameters=null;
        if(nonce!=null&&attempting==Route.WIFI_DIRECT&&UUID.fromString(nonce).version()==3)retireGroup(nonce);
        FramedSession old=candidate;candidate=null;bound=false;dialed=false;selecting=false;if(old!=null)old.close(UiText.EMPTY);platform.stopDirect();
    }
    public boolean send(TransferPacket packet){FramedSession wire=selected;if(wire==null||!currentFile(wire,nonce)||packet==null||!Objects.equals(generation,packet.connectionGeneration))return false;String path=selectedRoute;return wire.sendTransfer(packet,()->dataPaths.data(packet,true,path));}
    public boolean highBandwidth(){return selected!=null&&!closed&&!terminal&&("wifi-direct".equals(selectedRoute)||"lan".equals(selectedRoute));}
    /** The shared parent has no per-attempt nonce. Retain only a finite discard allowance for its retired authorization. */
    private void retireParent(){
        if(selected==chat&&chat.automaticFilesSupported()){
            retiredParentBytes=FairRecordWriter.FILE_BUDGET;retiredParentControls=FairRecordWriter.MAX_FILE_ENTRIES+1;
        }
    }
    private synchronized void clearParentDrain(){retiredParentBytes=0;retiredParentControls=0;}
    private synchronized boolean discardRetiredParent(TransferPacket packet){
        if(!valid()||selected==chat||packet==null||!Objects.equals(generation,packet.connectionGeneration))return false;
        if(packet.kind==TransferPacket.Kind.DATA){if(packet.data.length>retiredParentBytes)return false;retiredParentBytes-=packet.data.length;}
        else{if(retiredParentControls==0)return false;retiredParentControls--;}
        return true;
    }
    public void receiveChatTransfer(TransferPacket packet){
        if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration)){deliver(packet,chat,selectedRoute,nonce);return;}
        if(discardRetiredParent(packet))return;
        post(()->{startActor();if(valid()&&selected==null&&!chat.automaticFilesSupported())selectLegacy();if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration))deliver(packet,chat,selectedRoute,nonce);else if(!discardRetiredParent(packet))fail(new IOException("Unexpected chat FILE packet"),true);});
    }
    private boolean currentFile(FramedSession wire,String token){return wire!=null&&valid()&&selected==wire&&Objects.equals(nonce,token)&&(!parentRecovery||wire!=retiredParentFile);}
    private void deliver(TransferPacket packet,FramedSession wire,String route,String token){BooleanSupplier current=()->currentFile(wire,token);if(!current.getAsBoolean())return;dataPaths.data(packet,false,route);listener.receive(packet,current);}
    public void rememberHistory(AttachmentRecord record){dataPaths.remember(record);}
    public AttachmentRecord decorate(AttachmentRecord record)throws IOException{return dataPaths.decorate(record);}
    public TransferPath activityPath(){FileRouteHandover current=handover;return current!=null&&current.busy()?current.activityPath():null;}
    public String route(){return selectedRoute;}
    /** Live UI state only; pending routes are never persisted as completed data paths. */
    public TransferPath pendingPath(){
        if(closed||terminal)return TransferPath.DISCONNECTED;
        if(selected!=null&&!refreshing)return "wifi-direct".equals(selectedRoute)?TransferPath.WIFI_DIRECT:"lan".equals(selectedRoute)?TransferPath.LAN_UNCONFIRMED:TransferPath.BLUETOOTH;
        if(pathProbe!=null||groupProbe!=null||refreshing||attempting==null)return TransferPath.SELECTING;
        return attempting==Route.WIFI_DIRECT?TransferPath.PENDING_WIFI_DIRECT:attempting==Route.LAN?TransferPath.PENDING_LAN:TransferPath.PENDING_BLUETOOTH;
    }
    public String diagnostics(){
        String state=closed?"closed":terminal?"failed":pathProbe!=null?"checking_existing":groupProbe!=null?"checking_direct":selected!=null?(refreshing?"rechecking":"ready"):attempting!=null?"negotiating":ready!=null&&!ready.isDone()?"requesting":lastRouteFailure.isEmpty()?"idle":"failed";
        FramedSession wire=selected;
        StringBuilder wireReport=new StringBuilder("file_wire_schema=1\nfile_wire_kind="+(wire==null?"none":wire==chat?"chat":"dedicated")+"\n");
        if(wire!=null)for(String line:wire.diagnostics().split("\n"))if(!line.isEmpty())wireReport.append("file_wire_").append(line).append('\n');
        return "file_transport="+selectedRoute+"\nfile_route_pending="+(selected==null)+"\nfile_route_state="+state+"\nfile_attempt_route="+(attempting==null?"":name(attempting))+"\nfile_direct_outcome="+directOutcome+"\nfile_direct_method="+directMethod+"\nfile_direct_skip_reason="+directSkipReason+"\nfile_route_last_failure="+lastRouteFailure+"\nfile_existing_wifi="+(parentHotspot?"confirmed_hotspot":"unconfirmed")+"\n"+(handover==null?"file_handover_supported=false\n":handover.diagnostics())+wireReport+platform.diagnostics();
    }
    public void abort(){post(()->{String token=nonce;Route route=attempting;fail(new IOException("FILE route canceled"),false);if(valid()&&token!=null)control(FileRoutePacket.Kind.ABORT,token,route,false,Collections.emptyList());});}
    public void close(){
        synchronized(this){if(closed)return;closed=true;parentFinished.completeExceptionally(new IOException("FILE routes closed"));pathProbe=null;pathSnapshot=null;parentHotspot=false;verifiedPathNonce=null;retiredParentBytes=0;retiredParentControls=0;if(ready!=null&&!ready.isDone())ready.completeExceptionally(new IOException("FILE routes closed"));}
        if(handover!=null)handover.close();selected=null;selectedRoute="";FramedSession old=candidate;if(old!=null)old.close(UiText.EMPTY);platform.close();for(StreamConnection connection:pendingConnections)dispose(connection);pendingConnections.clear();for(CompletableFuture<Void> transition:laneTransitions)transition.completeExceptionally(new IOException("FILE routes closed"));laneTransitions.clear();actor.shutdownNow();Arrays.fill(noiseKey,(byte)0);
    }
    private static IOException asIo(Throwable error){return error instanceof IOException?(IOException)error:new IOException("FILE transport unavailable",error);}
    private static void dispose(StreamConnection connection){try{connection.close();}catch(IOException ignored){}}
}
