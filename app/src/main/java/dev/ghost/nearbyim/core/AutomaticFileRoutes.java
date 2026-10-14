package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One authenticated FILE route for an approved chat; chat and engine generations never change. */
public final class AutomaticFileRoutes implements AutoCloseable {
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
    private CompletableFuture<Route> ready;
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
        if(selected!=null&&!refreshing)return CompletableFuture.completedFuture(parseRoute(selectedRoute));
        if(ready==null||ready.isDone()){ready=new CompletableFuture<>();post(()->{startActor();if(!valid())return;if(!chat.automaticFilesSupported()){selectLegacy();return;}if(lead){if(attempting==null)begin(platform.directSupported()?Route.WIFI_DIRECT:Route.LAN);}else if(attempting==null)control(FileRoutePacket.Kind.REQUEST,UUID.randomUUID().toString(),Route.LAN,false,Collections.emptyList());});}
        return ready;
    }
    /** Re-evaluate a prior fallback only when both engines are idle. Call before enqueueing a new batch. */
    public synchronized CompletableFuture<Route> prepareNewBatch(){
        if(selected==null||closed||terminal)return ensureReady();
        if(!valid())return failedFuture(new IOException("Approved FILE route unavailable"));
        if("wifi-direct".equals(selectedRoute)||!chat.automaticFilesSupported()||listener.busy())return CompletableFuture.completedFuture(parseRoute(selectedRoute));
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
        refreshing=false;refreshRequest=null;recheckNonce=null;if(recheckDeadline!=null){recheckDeadline.cancel(false);recheckDeadline=null;}
        if(selected!=null)complete(parseRoute(selectedRoute));
    }
    private void requestRecheck(){
        if(selected==null||listener.busy()||"wifi-direct".equals(selectedRoute)){finishRecheck();return;}
        if(recheckNonce!=null)return;
        refreshing=true;recheckNonce=UUID.randomUUID().toString();control(FileRoutePacket.Kind.RECHECK,recheckNonce,parseRoute(selectedRoute),false,Collections.emptyList());watchRecheck();
    }
    private boolean receiveRecheck(FileRoutePacket p){
        switch(p.kind){
            case REFRESH_REQUEST:
                if(!lead)return true;
                if(selected==null)return true;
                if(listener.busy()||"wifi-direct".equals(selectedRoute))control(FileRoutePacket.Kind.BUSY,p.nonce,parseRoute(selectedRoute),false,Collections.emptyList());else requestRecheck();return true;
            case RECHECK:
                if(lead){fail(new IOException("Invalid FILE recheck coordinator"),true);return true;}
                if(selected==null||listener.busy()){control(FileRoutePacket.Kind.BUSY,p.nonce,p.route,false,Collections.emptyList());return true;}
                refreshing=true;recheckNonce=p.nonce;watchRecheck();control(FileRoutePacket.Kind.RECHECK_ACK,p.nonce,p.route,false,Collections.emptyList());return true;
            case RECHECK_ACK:
                if(!lead||!Objects.equals(recheckNonce,p.nonce))return true;
                if(listener.busy()){control(FileRoutePacket.Kind.BUSY,p.nonce,p.route,false,Collections.emptyList());finishRecheck();return true;}
                selected=null;selectedRoute="";finishRecheck();begin(platform.directSupported()?Route.WIFI_DIRECT:Route.LAN);return true;
            case BUSY:
                if(Objects.equals(recheckNonce,p.nonce)||Objects.equals(refreshRequest,p.nonce))finishRecheck();return true;
            default:return false;
        }
    }
    private static <T> CompletableFuture<T> failedFuture(IOException e){CompletableFuture<T> f=new CompletableFuture<>();f.completeExceptionally(e);return f;}
    private void selectLegacy(){selected=chat;selectedRoute=chatRoute;complete(parseRoute(chatRoute));}
    private static Route parseRoute(String route){return "wifi-direct".equals(route)?Route.WIFI_DIRECT:"lan".equals(route)?Route.LAN:Route.BLUETOOTH;}
    private static String name(Route r){return r==Route.WIFI_DIRECT?"wifi-direct":r==Route.LAN?"lan":"bluetooth";}
    private boolean control(FileRoutePacket.Kind kind,String token,Route route,boolean terminal,List<Endpoint> endpoints){return chat.sendFileRoute(new FileRoutePacket(kind,token,generation,route,terminal,endpoints));}
    private void begin(Route route){
        resetCandidate();nonce=UUID.randomUUID().toString();attempting=route;
        if(!control(FileRoutePacket.Kind.BEGIN,nonce,route,false,Collections.emptyList())){fail(new IOException("FILE control unavailable"),true);return;}enter();
    }
    private void enter(){
        phaseFailed=false;
        if(!valid()){fail(new IOException("Approval revoked"),true);return;}
        String token=nonce;
        deadline=actor.schedule(()->{if(Objects.equals(token,nonce)&&selected==null)attemptFailed(new IOException("FILE route timed out"));},attempting==Route.WIFI_DIRECT?15:attempting==Route.LAN?8:15,TimeUnit.SECONDS);
        if(attempting==Route.WIFI_DIRECT){
            if(!platform.directSupported()){attemptFailed(new IOException("Direct unsupported"));return;}
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
        if(receiveRecheck(p))return;
        if(p.kind==FileRoutePacket.Kind.REQUEST){if(lead&&selected==null&&attempting==null)begin(platform.directSupported()?Route.WIFI_DIRECT:Route.LAN);return;}
        if(p.kind==FileRoutePacket.Kind.BEGIN){
            if(lead){fail(new IOException("Invalid FILE coordinator"),true);return;}
            if(selected!=null&&!refreshing)return;
            Route previous=refreshing?null:attempting;
            boolean ordered=p.route==Route.WIFI_DIRECT?previous==null:p.route==Route.LAN?previous==null||previous==Route.WIFI_DIRECT:previous==Route.LAN&&"bluetooth".equals(chatRoute);
            if(!ordered){fail(new IOException("Invalid FILE fallback provenance or order"),true);return;}
            if(refreshing){selected=null;selectedRoute="";finishRecheck();}resetCandidate();nonce=p.nonce;attempting=p.route;enter();return;
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
            case FAIL:if(p.terminal)fail(new IOException("Peer rejected FILE identity binding"),true);else if(lead)attemptFailed(new IOException("Peer FILE route unavailable"));break;
            case SELECT:
                if(lead){fail(new IOException("Invalid FILE selector"),true);return;}
                if(extra()&&!bound){fail(new IOException("Unbound FILE selection"),true);return;}
                selected=extra()?candidate:chat;selectedRoute=selected==chat?chatRoute:name(attempting);cancelDeadline();selection(FileRoutePacket.Kind.SELECT_ACK);complete(attempting);break;
            case SELECT_ACK:if(lead&&selecting){selected=extra()?candidate:chat;selectedRoute=selected==chat?chatRoute:name(attempting);cancelDeadline();complete(attempting);}break;
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
                if(route==Route.LAN){cancelDeadline();deadline=actor.schedule(()->{if(current(token,route))attemptFailed(new IOException("FILE lane handshake timed out"));},15,TimeUnit.SECONDS);}candidate.start();
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
        public void onTransfer(TransferPacket packet){if(valid()&&selected==wire&&generation.equals(packet.connectionGeneration))listener.receive(packet);else post(()->fail(new IOException("FILE packet before selection or wrong generation"),true));}
        public void onText(Frame f){post(()->fail(new IOException("Text on FILE lane"),true));}public void onAck(String id){post(()->fail(new IOException("Receipt on FILE lane"),true));}
        public void onFailure(IOException e){post(()->{if(live()&&(e instanceof NoiseRecordChannel.AuthenticationException))fail(e,true);});}
        public void onClosed(UiText reason){post(()->{if(!live()||refreshing)return;if(selected==wire)fail(new IOException("FILE lane interrupted"),false);else attemptFailed(new IOException("FILE lane handshake failed"));});}
    }
    private void attemptFailed(IOException error){
        if(!valid()||selected!=null)return;
        if(!lead){control(FileRoutePacket.Kind.FAIL,nonce,attempting,false,Collections.emptyList());resetCandidate();phaseFailed=true;return;}
        Route next=attempting==Route.WIFI_DIRECT?Route.LAN:Route.BLUETOOTH;
        if(attempting==Route.BLUETOOTH){fail(error,false);return;}begin(next);
    }
    private void complete(Route route){synchronized(this){if(ready!=null&&!ready.isDone())ready.complete(route);}listener.changed();}
    private void fail(IOException error,boolean security){
        if(closed)return;
        if(security&&!terminal&&nonce!=null&&generation!=null&&chat.isReady())control(FileRoutePacket.Kind.FAIL,nonce,attempting,true,Collections.emptyList());
        terminal|=security;selected=null;selectedRoute="";finishRecheck();resetCandidate();attempting=null;nonce=null;
        if(security)platform.close();synchronized(this){if(ready!=null&&!ready.isDone())ready.completeExceptionally(error);}listener.failed(error);listener.changed();
    }
    private void cancelDeadline(){if(deadline!=null){deadline.cancel(false);deadline=null;}}
    private void resetCandidate(){cancelDeadline();FramedSession old=candidate;candidate=null;bound=false;dialed=false;selecting=false;if(old!=null)old.close(UiText.EMPTY);platform.stopDirect();}
    public boolean send(TransferPacket packet){FramedSession wire=selected;if(wire==null||closed||terminal||!chat.isReady()||!approved.getAsBoolean()||packet==null||!Objects.equals(generation,packet.connectionGeneration))return false;return wire.sendTransfer(packet);}
    public void receiveChatTransfer(TransferPacket packet){
        if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration)){listener.receive(packet);return;}
        post(()->{startActor();if(valid()&&selected==null&&!chat.automaticFilesSupported())selectLegacy();if(valid()&&selected==chat&&packet!=null&&Objects.equals(generation,packet.connectionGeneration))listener.receive(packet);else fail(new IOException("Unexpected chat FILE packet"),true);});
    }
    public String route(){return selectedRoute;}
    public String diagnostics(){return "file_transport="+selectedRoute+"\nfile_route_pending="+(selected==null)+"\n";}
    public void abort(){post(()->{String token=nonce;Route route=attempting;fail(new IOException("FILE route canceled"),false);if(valid()&&token!=null)control(FileRoutePacket.Kind.ABORT,token,route,false,Collections.emptyList());});}
    public void close(){
        synchronized(this){if(closed)return;closed=true;if(ready!=null&&!ready.isDone())ready.completeExceptionally(new IOException("FILE routes closed"));}
        selected=null;selectedRoute="";FramedSession old=candidate;if(old!=null)old.close(UiText.EMPTY);platform.close();for(StreamConnection connection:pendingConnections)dispose(connection);pendingConnections.clear();for(CompletableFuture<Void> transition:laneTransitions)transition.completeExceptionally(new IOException("FILE routes closed"));laneTransitions.clear();actor.shutdownNow();Arrays.fill(noiseKey,(byte)0);
    }
    private static IOException asIo(Throwable error){return error instanceof IOException?(IOException)error:new IOException("FILE transport unavailable",error);}
    private static void dispose(StreamConnection connection){try{connection.close();}catch(IOException ignored){}}
}
