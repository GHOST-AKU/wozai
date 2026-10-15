package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One authenticated FILE route for an approved chat; chat and engine generations never change. */
public final class AutomaticFileRoutes implements AutoCloseable {
    /** Target discovery after service advertising, without extending on duplicate callbacks. */
    public static final long DIRECT_DISCOVERY_TIMEOUT_MS=5_000;
    /** Total native startup/group/socket budget; identity binding gets a separate deadline. */
    public static final long DIRECT_OPEN_TIMEOUT_MS=50_000;
    private static final long LANE_HANDSHAKE_TIMEOUT_MS=15_000;
    public enum Route { WIFI_DIRECT, LAN, BLUETOOTH }
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
        default List<Endpoint> currentLanEndpoints(){return null;}
        void stopDirect();void close();
    }
    public interface Listener {void receive(TransferPacket packet);void changed();void failed(IOException error);default boolean busy(){return false;}}
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
    private long retiredParentBytes;private int retiredParentControls;
    private volatile boolean refreshing;private String refreshRequest,recheckNonce;private ScheduledFuture<?> recheckDeadline;
    public AutomaticFileRoutes(FramedSession chat,String localId,String nickname,DeviceIdentity identity,byte[] noiseKey,String remoteId,String remoteKey,String chatRoute,BooleanSupplier approved,Platform platform,Listener listener){
        this.chat=Objects.requireNonNull(chat);this.localId=Objects.requireNonNull(localId).toLowerCase(Locale.ROOT);this.nickname=nickname;this.identity=Objects.requireNonNull(identity);this.noiseKey=noiseKey.clone();this.remoteId=Objects.requireNonNull(remoteId).toLowerCase(Locale.ROOT);this.remoteKey=Objects.requireNonNull(remoteKey);this.chatRoute=Objects.requireNonNull(chatRoute);this.approved=Objects.requireNonNull(approved);this.platform=Objects.requireNonNull(platform);this.listener=Objects.requireNonNull(listener);lead=this.localId.compareTo(this.remoteId)<0;actor.setRemoveOnCancelPolicy(true);
    }
    private void post(Runnable action){
        if(closed)return;
        if(!pending.tryAcquire()){close();listener.failed(new IOException("FILE control queue full"));return;}
        try{actor.execute(()->{try{if(!closed)action.run();}catch(RuntimeException e){fail(new IOException("FILE routing failed",e),true);}finally{pending.release();}});}catch(RejectedExecutionException ignored){pending.release();}
    }
    private boolean valid(){return !closed&&!terminal&&chat.isReady()&&approved.getAsBoolean()&&Objects.equals(generation,chat.connectionGeneration());}
    public void start(){post(this::startActor);}
    private void startActor(){
        if(started)return;started=true;generation=chat.connectionGeneration();
        if(!valid()){fail(new IOException("Approved chat unavailable"),true);return;}
        platform.prepareLan(endpoints->post(()->{if(endpoints==null||endpoints.size()>8){fail(new IOException("Too many FILE endpoints"),true);return;}endpointsPrepared=true;localEndpoints=Collections.unmodifiableList(new ArrayList<>(endpoints));if(attempting==Route.LAN)sendEndpoints();}),connection->postAccepted(connection));
        actor.scheduleWithFixedDelay(()->{if(!closed&&!terminal&&!valid())fail(new IOException("Chat closed or approval revoked"),true);},1,1,TimeUnit.SECONDS);
    }
    public synchronized CompletableFuture<Route> ensureReady(){
        if(closed||terminal||!chat.isReady()||!approved.getAsBoolean())return failedFuture(new IOException("Approved FILE route unavailable"));
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
        if(path==null){begin(initialRoute());return;}
        // ENDPOINTS with a version-3 UUID is a backwards-compatible path advertisement.
        // Legacy attempts use random version-4 UUIDs, so older peers safely ignore it.
        String token=UUID.nameUUIDFromBytes((generation+UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();
        pathSnapshot=path;pathProbe=token;listener.changed();pathDeadline=actor.schedule(()->{if(Objects.equals(pathProbe,token)&&valid()){pathProbe=null;pathSnapshot=null;begin(initialRoute());}},3,TimeUnit.SECONDS);
        if(!control(FileRoutePacket.Kind.ENDPOINTS,token,Route.LAN,false,pathEndpoints(path)))fail(new IOException("FILE path probe unavailable"),false);
    }
    private boolean receivePath(FileRoutePacket p){
        if(p.kind!=FileRoutePacket.Kind.ENDPOINTS||UUID.fromString(p.nonce).version()!=3)return false;
        if(!"lan".equals(chatRoute)||p.route!=Route.LAN)return true;
        if(lead){
            if(!Objects.equals(pathProbe,p.nonce))return true;
            SocketPath path=pathSnapshot;parentHotspot=path!=null&&path.confirmsHotspot(decodePath(p.endpoints));verifiedPathNonce=parentHotspot?p.nonce:null;
            pathProbe=null;pathSnapshot=null;if(pathDeadline!=null){pathDeadline.cancel(false);pathDeadline=null;}begin(initialRoute());
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
    private boolean dedicatedDirect(){return selected!=null&&selected!=chat&&"wifi-direct".equals(selectedRoute);}
    private static Route parseRoute(String route){return "wifi-direct".equals(route)?Route.WIFI_DIRECT:"lan".equals(route)?Route.LAN:Route.BLUETOOTH;}
    private static String name(Route r){return r==Route.WIFI_DIRECT?"wifi-direct":r==Route.LAN?"lan":"bluetooth";}
    private boolean control(FileRoutePacket.Kind kind,String token,Route route,boolean terminal,List<Endpoint> endpoints){return chat.sendFileRoute(new FileRoutePacket(kind,token,generation,route,terminal,endpoints));}
    private void begin(Route route){
        resetCandidate();nonce=parentHotspot&&route==Route.LAN?verifiedPathNonce:UUID.randomUUID().toString();attempting=route;
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
            platform.openDirect(lead,opened->opened(opened,token,Route.WIFI_DIRECT),error->post(()->{if(current(token,Route.WIFI_DIRECT))attemptFailed(asIo(error));}));
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
        if(receivePath(p))return;
        if(receiveRecheck(p))return;
        if(p.kind==FileRoutePacket.Kind.REQUEST){if(lead&&selected==null&&attempting==null)chooseInitialRoute();return;}
        if(p.kind==FileRoutePacket.Kind.BEGIN){
            if(lead){fail(new IOException("Invalid FILE coordinator"),true);return;}
            if(selected!=null&&!refreshing)return;
            Route previous=refreshing?null:attempting;
            boolean ordered=p.route==Route.WIFI_DIRECT?previous==null:p.route==Route.LAN?previous==null||previous==Route.WIFI_DIRECT:previous==Route.LAN&&"bluetooth".equals(chatRoute);
            if(!ordered){fail(new IOException("Invalid FILE fallback provenance or order"),true);return;}
            parentHotspot=p.route==Route.LAN&&parentHotspot&&Objects.equals(verifiedPathNonce,p.nonce);
            if(previous==null){lastRouteFailure="";directSkipReason="";directOutcome=parentHotspot?"not_needed_existing_hotspot":p.route==Route.WIFI_DIRECT?"not_attempted":"peer_skipped";}
            else if(previous==Route.WIFI_DIRECT&&p.route==Route.LAN&&directOutcome.equals("attempting")){directOutcome="failed";lastRouteFailure="peer_unavailable";}
            if(refreshing){synchronized(this){retireParent();selected=null;selectedRoute="";}finishRecheck();}resetCandidate();nonce=p.nonce;attempting=p.route;enter();return;
        }
        if(!Objects.equals(nonce,p.nonce)||p.route!=attempting)return;
        switch(p.kind){
            case ENDPOINTS:
                if(lead&&attempting==Route.LAN&&!"lan".equals(chatRoute)&&!dialed){
                    dialed=true;if(p.endpoints.isEmpty()){attemptFailed(new IOException("No LAN endpoints"));return;}
                    String token=nonce;
                    platform.connectLan(p.endpoints,connection->opened(new Opened(connection,false),token,Route.LAN),error->post(()->{if(current(token,Route.LAN))attemptFailed(asIo(error));}));
                }break;
            case ABORT:fail(new IOException("Peer canceled FILE route"),false);break;
            case FAIL:if(p.terminal)fail(new IOException("Peer rejected FILE identity binding"),true);else if(lead)attemptFailed(new IOException("Peer FILE route unavailable"),"peer_unavailable");break;
            case SELECT:
                if(lead){fail(new IOException("Invalid FILE selector"),true);return;}
                if(extra()&&!bound){fail(new IOException("Unbound FILE selection"),true);return;}
                selected=extra()?candidate:chat;selectedRoute=selected==chat?(parentHotspot?"wifi-direct":chatRoute):name(attempting);if(selected==chat)clearParentDrain();cancelDeadline();selection(FileRoutePacket.Kind.SELECT_ACK);complete(attempting);break;
            case SELECT_ACK:if(lead&&selecting){selected=extra()?candidate:chat;selectedRoute=selected==chat?(parentHotspot?"wifi-direct":chatRoute):name(attempting);if(selected==chat)clearParentDrain();cancelDeadline();complete(attempting);}break;
            default:fail(new IOException("Unexpected parent FILE control"),true);
        }
    }
    private boolean extra(){return attempting==Route.WIFI_DIRECT||attempting==Route.LAN&&!"lan".equals(chatRoute);}
    private boolean selection(FileRoutePacket.Kind kind){FileRoutePacket p=new FileRoutePacket(kind,nonce,generation,attempting,false,Collections.emptyList());return extra()?candidate!=null&&candidate.sendFileRoute(p):chat.sendFileRoute(p);}
    private void select(){selecting=true;if(!selection(FileRoutePacket.Kind.SELECT))fail(new IOException("FILE selection failed"),true);}
    private void postAccepted(StreamConnection connection){String token=nonce;opened(new Opened(connection,true),token,Route.LAN);}
    private void opened(Opened opened,String token,Route route){
        pendingConnections.add(opened.connection);
        if(closed){pendingConnections.remove(opened.connection);dispose(opened.connection);return;}
        post(()->{pendingConnections.remove(opened.connection);if(!current(token,route)||candidate!=null){dispose(opened.connection);return;}
            try{
                Lane listener=new Lane(token,route,!opened.incoming);
                candidate=FramedSession.secure(opened.connection,localId,nickname,identity,noiseKey,!opened.incoming,listener,false,true);listener.wire=candidate;candidate.restrictToFileLane();
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
            if(selected==wire&&generation.equals(packet.connectionGeneration))listener.receive(packet);
            else post(()->{if(live())fail(new IOException("FILE packet before selection or wrong generation"),true);});
        }
        public void onText(Frame f){post(()->{if(live())fail(new IOException("Text on FILE lane"),true);});}public void onAck(String id){post(()->{if(live())fail(new IOException("Receipt on FILE lane"),true);});}
        public void onFailure(IOException e){post(()->{if(live()&&(e instanceof NoiseRecordChannel.AuthenticationException))fail(e,true);});}
        public void onClosed(UiText reason){post(()->{if(!live()||refreshing)return;if(selected==wire)fail(new IOException("FILE lane interrupted"),false);else attemptFailed(new IOException("FILE lane handshake failed"),"handshake_failed");});}
    }
    private void attemptFailed(IOException error){attemptFailed(error,"platform_error");}
    private void attemptFailed(IOException error,String reason){
        if(!valid()||selected!=null)return;
        lastRouteFailure=reason;if(attempting==Route.WIFI_DIRECT&&!directOutcome.equals("skipped"))directOutcome="failed";
        if(!lead){control(FileRoutePacket.Kind.FAIL,nonce,attempting,false,Collections.emptyList());resetCandidate();phaseFailed=true;return;}
        Route next=attempting==Route.WIFI_DIRECT?Route.LAN:Route.BLUETOOTH;
        if(attempting==Route.BLUETOOTH){fail(error,false);return;}begin(next);
    }
    private void complete(Route route){if(route==Route.WIFI_DIRECT)directOutcome="selected";synchronized(this){if(ready!=null&&!ready.isDone())ready.complete(route);}listener.changed();}
    private void fail(IOException error,boolean security){
        CompletableFuture<Route> retiring;boolean notifyPeer;
        synchronized(this){
            if(closed)return;
            pathProbe=null;pathSnapshot=null;parentHotspot=false;verifiedPathNonce=null;if(pathDeadline!=null){pathDeadline.cancel(false);pathDeadline=null;}
            lastRouteFailure=security?"security_error":"route_interrupted";
            if(attempting==Route.WIFI_DIRECT&&directOutcome.equals("attempting"))directOutcome="failed";
            notifyPeer=security&&!terminal&&nonce!=null&&generation!=null&&chat.isReady();
            if(!security)retireParent();else{retiredParentBytes=0;retiredParentControls=0;}
            terminal|=security;retiring=ready;ready=null;selected=null;selectedRoute="";refreshing=false;
        }
        if(notifyPeer)control(FileRoutePacket.Kind.FAIL,nonce,attempting,true,Collections.emptyList());
        finishRecheck();resetCandidate();attempting=null;nonce=null;
        if(security)platform.close();if(retiring!=null&&!retiring.isDone())retiring.completeExceptionally(error);listener.failed(error);listener.changed();
    }
    private void cancelDeadline(){if(deadline!=null){deadline.cancel(false);deadline=null;}}
    private void resetCandidate(){cancelDeadline();FramedSession old=candidate;candidate=null;bound=false;dialed=false;selecting=false;if(old!=null)old.close(UiText.EMPTY);platform.stopDirect();}
    public boolean send(TransferPacket packet){FramedSession wire=selected;if(wire==null||closed||terminal||!chat.isReady()||!approved.getAsBoolean()||packet==null||!Objects.equals(generation,packet.connectionGeneration))return false;return wire.sendTransfer(packet);}
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
        if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration)){listener.receive(packet);return;}
        if(discardRetiredParent(packet))return;
        post(()->{startActor();if(valid()&&selected==null&&!chat.automaticFilesSupported())selectLegacy();if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration))listener.receive(packet);else if(!discardRetiredParent(packet))fail(new IOException("Unexpected chat FILE packet"),true);});
    }
    public String route(){return selectedRoute;}
    /** Live UI state only; pending routes are never persisted as completed data paths. */
    public TransferPath pendingPath(){
        if(closed||terminal)return TransferPath.DISCONNECTED;
        if(selected!=null&&!refreshing)return "wifi-direct".equals(selectedRoute)?TransferPath.WIFI_DIRECT:"lan".equals(selectedRoute)?TransferPath.LAN_UNCONFIRMED:TransferPath.BLUETOOTH;
        if(pathProbe!=null||refreshing||attempting==null)return TransferPath.SELECTING;
        return attempting==Route.WIFI_DIRECT?TransferPath.PENDING_WIFI_DIRECT:attempting==Route.LAN?TransferPath.PENDING_LAN:TransferPath.PENDING_BLUETOOTH;
    }
    public String diagnostics(){
        String state=closed?"closed":terminal?"failed":pathProbe!=null?"checking_existing":selected!=null?(refreshing?"rechecking":"ready"):attempting!=null?"negotiating":ready!=null&&!ready.isDone()?"requesting":lastRouteFailure.isEmpty()?"idle":"failed";
        return "file_transport="+selectedRoute+"\nfile_route_pending="+(selected==null)+"\nfile_route_state="+state+"\nfile_attempt_route="+(attempting==null?"":name(attempting))+"\nfile_direct_outcome="+directOutcome+"\nfile_direct_skip_reason="+directSkipReason+"\nfile_route_last_failure="+lastRouteFailure+"\nfile_existing_wifi="+(parentHotspot?"confirmed_hotspot":"unconfirmed")+"\n"+platform.diagnostics();
    }
    public void abort(){post(()->{String token=nonce;Route route=attempting;fail(new IOException("FILE route canceled"),false);if(valid()&&token!=null)control(FileRoutePacket.Kind.ABORT,token,route,false,Collections.emptyList());});}
    public void close(){
        synchronized(this){if(closed)return;closed=true;pathProbe=null;pathSnapshot=null;parentHotspot=false;verifiedPathNonce=null;retiredParentBytes=0;retiredParentControls=0;if(ready!=null&&!ready.isDone())ready.completeExceptionally(new IOException("FILE routes closed"));}
        selected=null;selectedRoute="";FramedSession old=candidate;if(old!=null)old.close(UiText.EMPTY);platform.close();for(StreamConnection connection:pendingConnections)dispose(connection);pendingConnections.clear();for(CompletableFuture<Void> transition:laneTransitions)transition.completeExceptionally(new IOException("FILE routes closed"));laneTransitions.clear();actor.shutdownNow();Arrays.fill(noiseKey,(byte)0);
    }
    private static IOException asIo(Throwable error){return error instanceof IOException?(IOException)error:new IOException("FILE transport unavailable",error);}
    private static void dispose(StreamConnection connection){try{connection.close();}catch(IOException ignored){}}
}
