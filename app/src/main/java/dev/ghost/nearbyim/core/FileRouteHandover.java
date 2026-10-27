package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** One parent-authorized migration. All state transitions run on the routing actor. */
final class FileRouteHandover implements AutoCloseable {
    private static final int QUALITY_REQUEST_LIMIT=3;
    private static final long QUALITY_REQUEST_RETRY_NS=1_000_000_000L;
    interface Host {
        boolean valid();boolean lead();String generation();String routeToken();String parentRoute();String route();
        FramedSession parent();FramedSession selected();ClientAttachmentTransfers files();
        ScheduledExecutorService timer();void post(Runnable action);void changed();
        List<AutomaticFileRoutes.Endpoint> lanEndpoints();
        void dial(List<AutomaticFileRoutes.Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed);
        FramedSession lane(StreamConnection connection,boolean initiator,FramedSession.Listener listener)throws IOException;
        boolean expectedPeer(FramedSession wire,Frame hello);
        void install(FramedSession wire,String nonce,boolean fault,String route);
        void transfer(FramedSession wire,String token,TransferPacket packet);boolean recoverParent();boolean parentLost();void parentFinished();void failure(IOException error,boolean security);
    }
    private final Host host;private final ArrayDeque<String> events=new ArrayDeque<>();private final long created=System.nanoTime();
    private void trace(String event){synchronized(events){events.add((System.nanoTime()-created)/1_000_000+":"+phase+":"+event);while(events.size()>64)events.remove();}}
    private String trace(){synchronized(events){return String.join(";",events);}}
    private final FileLinkCapability capability;
    private final Set<String> retired=new LinkedHashSet<>();
    private volatile String phase="idle",lastReason="";
    private volatile int switches,recoveries;
    private volatile CompletableFuture<AutomaticFileRoutes.Route> ready;
    private String nonce;private boolean fault,peerPaused,closed,selecting;
    private boolean bluetoothFallback;private int attempts;private FramedSession backup;private boolean backupBound;
    private FileRouteResume lease;private boolean freezing;
    private ScheduledFuture<?> deadline,retry,monitor,candidateDeadline;
    private int candidateEpoch;
    private FileLinkProbe probe;private boolean probeSender,draining,dialing,retaining;private FileLinkPacket earlyPause;private long checkedAt,lastHeartbeat,peerHeartbeat;
    private long qualityRequestedAt;private int qualityRequests;private String qualityRequestRoute,qualityRequestState="idle",qualityAdmission="not_requested";
    private long peerRemaining,peerRate,peerCapacity,peerCost;private int peerPhase=3;
    private long comparisonRemaining,comparisonCost,comparisonCapacity,comparisonStarted;
    private FileRouteQualityPolicy.Sample[] comparison;
    private volatile FileRouteQualityPolicy.Decision qualityDecision;
    private String lastAssessmentNonce;
    private volatile String qualitySamples="";
    private volatile long assessmentNanos,pauseRequestedAt,pauseNanos,probeStartedAt,probeNanos,setupNanos,decisionCost;

    private String endpointIdentity="";private String qualityResult="not_measured";private List<AutomaticFileRoutes.Endpoint> peerEndpoints=List.of();private long parentIdle;
    private volatile Binding binding;
    private static final class Binding {
        final String nonce;final FramedSession wire;volatile long expires=Long.MAX_VALUE;
        Binding(String nonce,FramedSession wire){this.nonce=nonce;this.wire=wire;}
    }
    FileRouteHandover(Host host){
        this.host=host;
        capability=new FileLinkCapability(host.lead(),host.generation(),packet->{if(!host.parent().sendFileRoute(packet))fail(new IOException("Migration capability unavailable"),false);},()->{
            host.parent().enableFileLink();advertiseStandby();FramedSession selected=host.selected();if(selected!=null)selected.enableFileLink();host.changed();
        });capability.start();
        monitor=host.timer().scheduleWithFixedDelay(this::tick,250,250,TimeUnit.MILLISECONDS);
    }
    private void tick(){
        if(closed||!supported()||!host.valid())return;
        FileTransferActivity activity=host.files().activity();long now=System.nanoTime();
        if(now-lastHeartbeat>=1_000_000_000L){lastHeartbeat=now;
            List<AutomaticFileRoutes.Endpoint> endpoints=host.lanEndpoints();String next=endpointKey(endpoints);if(!next.equals(endpointIdentity)){endpointIdentity=next;checkedAt=0;}
            if(controlWire()!=null){send(FileLinkPacket.activity(capability.token(),host.generation(),activity));advertiseStandby();}}
        if(host.parentLost()&&!busy()&&!host.files().automaticActive()&&peerPhase==3){if(parentIdle==0)parentIdle=now;else if(now-parentIdle>=FileRouteQualityPolicy.RECOVERY_NS){host.parentFinished();return;}}else parentIdle=0;
        if(!activity.sending||!Objects.equals(qualityRequestRoute,host.routeToken()))clearQualityRequest("inactive");
        if(qualityRequestedAt!=0&&qualityRequests>=QUALITY_REQUEST_LIMIT&&now-qualityRequestedAt>=qualityRequestBackoff()){
            // Expire even when the remaining file no longer justifies starting another comparison.
            checkedAt=now;clearQualityRequest("unanswered");trace("quality_request_unanswered");
        }
        if(busy()||host.selected()==host.parent()||!host.route().equals("wifi-direct"))return;
        boolean peerBusy=now-peerHeartbeat<3_000_000_000L&&peerPhase==2;
        if(activity.active&&!activity.busy&&!peerBusy&&activity.stalledNanos>=FileRouteQualityPolicy.STALL_NS){interrupted();return;}
        // A fresh comparison is scoped to this authenticated lane and lasts at most one minute.
        if(now-checkedAt<FileRouteQualityPolicy.CACHE_NS||!activity.sending||peerHeartbeat==0||now-peerHeartbeat>3_000_000_000L||peerBusy)return;
        if(FileRouteQualityPolicy.eligible(activity.activeNanos,activity.remaining,activity.rate,activity.busy)){
            if(host.lead())startQuality(true);else requestQuality(now);
        }
    }
    private long qualityRequestBackoff(){return QUALITY_REQUEST_RETRY_NS<<Math.min(qualityRequests-1,2);}
    private void requestQuality(long now){
        if(qualityRequestedAt!=0&&now-qualityRequestedAt<qualityRequestBackoff())return;
        if(qualityRequestedAt==0){qualityRequests=0;qualityRequestRoute=host.routeToken();}
        qualityRequests++;qualityRequestedAt=now;qualityRequestState="waiting";trace("quality_request:"+qualityRequests);
        send(FileLinkPacket.control(FileLinkPacket.Kind.REQUEST,host.routeToken(),host.generation(),2));
    }
    private void clearQualityRequest(String state){
        if(qualityRequestedAt==0)return;
        qualityRequestedAt=0;qualityRequestRoute=null;qualityRequestState=state;
    }
    private void deferQuality(String reason){
        if(!qualityAdmission.equals(reason)){qualityAdmission=reason;trace("quality_deferred:"+reason);}
    }
    private void startQuality(boolean localSender){
        if(busy()){deferQuality("migration_busy");return;}
        if(host.selected()==host.parent()||!host.route().equals("wifi-direct")){deferQuality("not_dedicated_direct");return;}
        FileTransferActivity activity=host.files().activity();long now=System.nanoTime();
        if(peerHeartbeat==0||now-peerHeartbeat>3_000_000_000L){deferQuality("peer_activity_unavailable");return;}
        if(peerPhase==2){deferQuality("peer_busy");return;}
        if(activity.busy){deferQuality("local_busy");return;}
        long capacity=activity.capacity==0?peerCapacity:peerCapacity==0?activity.capacity:Math.min(activity.capacity,peerCapacity);
        if(capacity==0){deferQuality("capacity_unknown");return;}
        comparisonRemaining=localSender?activity.remaining:peerRemaining;
        comparisonCost=Math.max(activity.switchCost,peerCost);comparisonCapacity=capacity;
        if(comparisonRemaining<=0){deferQuality("nothing_remaining");return;}
        if(comparisonCost>=FileRouteQualityPolicy.CACHE_NS){deferQuality("switch_cost_excessive");return;}
        double senderRate=localSender?activity.rate:peerRate;
        if(!FileRouteQualityPolicy.assessmentWorthwhile(comparisonRemaining,senderRate,comparisonCost)){
            checkedAt=now;deferQuality("assessment_cost_excessive");return;
        }
        qualityAdmission="accepted";
        probeSender=localSender;checkedAt=now;comparisonStarted=now;comparison=null;
        lastReason="comparing";begin(UUID.randomUUID().toString(),false);
    }
    boolean capability(FileRoutePacket packet){return capability.receive(packet);}
    boolean supported(){return capability.supported();}
    boolean busy(){return !phase.equals("idle")&&!phase.equals("failed")&&!phase.equals("closed");}
    CompletableFuture<AutomaticFileRoutes.Route> ready(){return ready;}
    void selected(){if(supported()&&host.selected()!=null)host.selected().enableFileLink();}
    void lanPrepared(){checkedAt=0;advertiseStandby();if(busy()&&!host.lead()&&backup==null)sendEndpoints();}
    private static String endpointKey(List<AutomaticFileRoutes.Endpoint> endpoints){if(endpoints==null)return "";List<String> keys=new ArrayList<>();for(AutomaticFileRoutes.Endpoint e:endpoints)keys.add(e.host+":"+e.port);Collections.sort(keys);return keys.toString();}
    private FramedSession controlWire(){return host.parent().isReady()?host.parent():backupBound?backup:host.parentLost()&&host.route().equals("lan")&&host.selected()!=null&&host.selected().isReady()?host.selected():null;}
    private void advertiseStandby(){
        if(!supported()||!host.parent().isReady())return;
        List<AutomaticFileRoutes.Endpoint> endpoints=host.lanEndpoints();if(endpoints!=null)host.parent().sendFileLink(FileLinkPacket.standby(capability.token(),host.generation(),endpoints));
    }
    void parentInterrupted(){
        String token=UUID.nameUUIDFromBytes((host.generation()+capability.token()+"parent-file-recovery").getBytes(java.nio.charset.StandardCharsets.US_ASCII)).toString();
        if(current(token))return;
        if(probe!=null){probe.close();probe=null;}
        lastReason="parent_interrupted";ready=new CompletableFuture<>();begin(token,true);
    }
    boolean interrupted(){trace("interrupted");
        if(closed||!supported()||!host.valid()||!busy()&&host.selected()==null)return false;
        if(busy()){
            if(!fault){fault=true;retaining=false;arm(FileRouteQualityPolicy.RECOVERY_NS);send(FileLinkPacket.control(FileLinkPacket.Kind.INTERRUPTED,nonce,host.generation(),0));if(probe!=null){probe.close();probe=null;}freeze();}
            advance();return true;
        }
        ready=new CompletableFuture<>();lastReason=host.route().equals("wifi-direct")?"direct_interrupted":"lan_interrupted";
        if(host.lead())begin(UUID.randomUUID().toString(),true);
        else{fault=true;phase="requesting";arm(FileRouteQualityPolicy.RECOVERY_NS);send(FileLinkPacket.control(FileLinkPacket.Kind.REQUEST,host.routeToken(),host.generation(),1));host.changed();}
        return true;
    }
    private void begin(String token,boolean lost){trace("begin");
        if(closed||!host.valid()||retired.contains(token))return;
        clearQualityRequest(lost?"interrupted":"accepted");
        if(!lost&&qualityRequests>0)qualityRequestState="accepted";
        if(nonce!=null&&!nonce.equals(token)){retire(nonce);disposeBackup();}
        disposeBackup();backupBound=false;
        if(ready==null||ready.isDone())ready=new CompletableFuture<>();
        if(!lost){
            lastAssessmentNonce=token;
            comparisonStarted=System.nanoTime();assessmentNanos=pauseNanos=probeStartedAt=probeNanos=setupNanos=pauseRequestedAt=decisionCost=0;
            qualityDecision=null;qualitySamples="";
        }
        nonce=token;fault=lost;bluetoothFallback=false;peerPaused=false;retaining=false;draining=false;dialing=false;lease=null;freezing=false;selecting=false;attempts=0;phase="preparing";
        arm(lost?FileRouteQualityPolicy.RECOVERY_NS:FileRouteQualityPolicy.SETUP_NS);
        if(host.lead()&&!host.parentLost())send(FileLinkPacket.prepare(nonce,host.generation(),lost?1:probeSender?0:2,host.routeToken()));
        ensureBackup();if(lost)freeze();
        if(earlyPause!=null){FileLinkPacket early=earlyPause;earlyPause=null;if(Objects.equals(token,early.nonce)&&!lost)freeze();}
        host.changed();
    }
    private void arm(long nanos){if(deadline!=null)deadline.cancel(false);deadline=host.timer().schedule(()->{if(busy())fail(new IOException("Automatic FILE recovery timed out"),false);},nanos,TimeUnit.NANOSECONDS);}
    private void ensureBackup(){
        if(!busy()||!host.valid())return;
        if(host.parentRoute().equals("lan")&&host.parent().isReady()){
            backup=host.parent();backup.enableFileLink();backupBound=true;backupReady();
        }else if(host.parentLost()){if(host.lead())dial(peerEndpoints);}
        else if(!host.lead())sendEndpoints();
    }
    private void sendEndpoints(){List<AutomaticFileRoutes.Endpoint> endpoints=host.lanEndpoints();if(endpoints!=null)send(FileLinkPacket.endpoints(nonce,host.generation(),endpoints));}
    private void dial(List<AutomaticFileRoutes.Endpoint> endpoints){
        if(!host.lead()||backup!=null||selecting||dialing)return;
        attempts++;if(endpoints.isEmpty()){candidateFailed(new IOException("No usable LAN endpoint"));return;}
        String token=nonce;phase="connecting";dialing=true;int epoch=++candidateEpoch;candidateBudget(epoch);
        host.dial(endpoints,connection->host.post(()->{if(!current(token)||epoch!=candidateEpoch||retaining||!phase.equals("connecting")||backup!=null){dispose(connection);return;}dialing=false;open(connection,true);}),error->host.post(()->{if(current(token)&&epoch==candidateEpoch&&!retaining&&phase.equals("connecting"))candidateFailed(asIo(error));}));
    }
    boolean accept(StreamConnection connection){
        if(!busy()||retaining||phase.equals("restoring")||host.lead()||backup!=null||!host.valid())return false;
        open(connection,false);return true;
    }
    private void open(StreamConnection connection,boolean initiator){
        if(!initiator)candidateBudget(++candidateEpoch);
        try{Lane listener=new Lane(nonce,initiator);FramedSession wire=host.lane(connection,initiator,listener);listener.wire=wire;backup=wire;wire.enableFileLink();phase="binding";wire.start();}
        catch(IOException error){dispose(connection);candidateFailed(error);}
    }
    private void candidateBudget(int epoch){
        cancelCandidateBudget();String token=nonce;
        candidateDeadline=host.timer().schedule(()->{if(current(token)&&epoch==candidateEpoch&&!backupBound)candidateFailed(new IOException("LAN candidate setup timed out"));},FileRouteQualityPolicy.SETUP_NS,TimeUnit.NANOSECONDS);
    }
    private void cancelCandidateBudget(){if(candidateDeadline!=null){candidateDeadline.cancel(false);candidateDeadline=null;}}
    private void backupReady(){if(!fault&&setupNanos==0)setupNanos=Math.max(0,System.nanoTime()-comparisonStarted);cancelCandidateBudget();if(lease!=null)send(FileLinkPacket.control(FileLinkPacket.Kind.PAUSED,nonce,host.generation(),0));if(!fault)drain();advance();}
    private void drain(){trace("drain");
        if(draining||lease!=null)return;if(!fault&&pauseRequestedAt==0)pauseRequestedAt=System.nanoTime();draining=true;String token=nonce;phase="pausing";
        host.files().quiesceOutgoing().whenComplete((value,error)->host.post(()->{
            if(!current(token))return;if(error!=null){fail(asIo(error),false);return;}
            FramedSession wire=host.selected();if(wire==null||!wire.sendFileLink(FileLinkPacket.control(FileLinkPacket.Kind.PAUSE,token,host.generation(),0)))interrupted();
        }));
    }
    private void freeze(){trace("freeze");
        if(freezing||lease!=null||!busy())return;if(!fault&&pauseRequestedAt==0)pauseRequestedAt=System.nanoTime();freezing=true;phase="pausing";String token=nonce;
        host.files().suspendForRouteChange().whenComplete((value,error)->host.post(()->{
            if(!current(token))return;freezing=false;if(error!=null){fail(asIo(error),false);return;}
            lease=value;send(FileLinkPacket.control(FileLinkPacket.Kind.PAUSED,nonce,host.generation(),0));advance();host.changed();
        }));
    }
    private void advance(){
        if(retaining){if(lease!=null&&peerPaused){binding=new Binding(nonce,host.selected());if(deadline!=null){deadline.cancel(false);deadline=null;}restore();}return;}
        if(!busy()||!backupBound||lease==null||!peerPaused||selecting)return;
        if(host.lead()){
            if(!fault){if(probe==null){startProbe();if(!probeSender)send(FileLinkPacket.control(FileLinkPacket.Kind.MEASURE,nonce,host.generation(),0));}return;}
            if(probe!=null){probe.close();probe=null;}
            selecting=true;phase="selecting";send(FileLinkPacket.control(FileLinkPacket.Kind.SELECT,nonce,host.generation(),0));
        }
    }
    void receive(FileLinkPacket packet,FramedSession wire){
        if(packet!=null&&packet.kind!=FileLinkPacket.Kind.PROGRESS&&packet.kind!=FileLinkPacket.Kind.STANDBY_ENDPOINTS&&packet.kind!=FileLinkPacket.Kind.PROBE_DATA&&packet.kind!=FileLinkPacket.Kind.PROBE_ACK)trace("receive:"+packet.kind);
        if(closed||!host.valid())return;
        if(packet==null||!host.generation().equals(packet.generation)){fail(new IOException("Migration parent generation mismatch"),true);return;}
        if(packet.kind==FileLinkPacket.Kind.RECOVERY_OFFER){receiveOffer(packet,wire);return;}
        if(!supported()){fail(new IOException("Unnegotiated FILE migration"),true);return;}
        if(packet.kind==FileLinkPacket.Kind.STANDBY_ENDPOINTS){
            if(wire!=controlWire()||!Objects.equals(packet.nonce,capability.token())){fail(new IOException("Unbound standby endpoint"),true);return;}
            if(!endpointKey(peerEndpoints).equals(endpointKey(packet.endpoints)))checkedAt=0;peerEndpoints=packet.endpoints;return;
        }
        if(packet.kind==FileLinkPacket.Kind.PROGRESS){
            if(wire!=controlWire()||!Objects.equals(packet.nonce,capability.token())){fail(new IOException("Unbound file activity"),true);return;}
            java.nio.ByteBuffer limits=java.nio.ByteBuffer.wrap(packet.data);long capacity=limits.getLong(),cost=limits.getLong();
            if(capacity<0||cost<0||cost>FileRouteQualityPolicy.CACHE_NS){fail(new IOException("Invalid file activity limits"),true);return;}
            peerHeartbeat=System.nanoTime();peerPhase=packet.number;peerRemaining=packet.value;peerRate=packet.nanos;peerCapacity=capacity;peerCost=cost;return;
        }
        if(packet.kind==FileLinkPacket.Kind.REQUEST){
            if(wire!=controlWire()){fail(new IOException("Unbound migration request"),true);return;}
            if(host.selected()==null||!Objects.equals(packet.nonce,host.routeToken()))return;
            if(host.lead()){if(packet.number==1)interrupted();else if(packet.number==2)startQuality(false);}return;
        }
        if(packet.kind==FileLinkPacket.Kind.PREPARE){
            if(host.selected()==null||!new String(packet.data,java.nio.charset.StandardCharsets.US_ASCII).equals(host.routeToken()))return;
            if(wire!=controlWire()||host.lead()||retired.contains(packet.nonce))return;
            if(current(packet.nonce)){if(packet.number==3){fallbackBluetooth(false);return;}if(backup==null)ensureBackup();return;}
            if(busy()&&!phase.equals("requesting")){fail(new IOException("Overlapping migration transaction"),true);return;}
            if(packet.number!=1){qualityAdmission="peer_initiated";qualityResult="comparing";}
            probeSender=packet.number==2;checkedAt=System.nanoTime();begin(packet.nonce,packet.number==1);return;
        }
        if(!current(packet.nonce)){
            if(retired.contains(packet.nonce)){
                // A late outcome only updates the last assessment's diagnostics, never its retired migration.
                if(packet.kind==FileLinkPacket.Kind.ABORT&&wire==controlWire()&&!host.lead()&&Objects.equals(lastAssessmentNonce,packet.nonce)){
                    qualityResult="peer_retained_direct";if(!busy())lastReason="peer_retained_direct";host.changed();
                }
                return;
            }
            // The selected FILE stream can overtake PREPARE on a different parent stream.
            if(packet.kind==FileLinkPacket.Kind.PAUSE&&wire==host.selected()&&!busy()&&earlyPause==null){earlyPause=packet;return;}
            fail(new IOException("Unexpected migration transaction: "+packet.kind+" in "+phase),true);return;
        }
        if(packet.kind.name().startsWith("PROBE_")){
            if(packet.kind!=FileLinkPacket.Kind.PROBE_RESULT&&wire!=host.selected()&&wire!=backup){fail(new IOException("Probe on unselected stream"),true);return;}
            if(packet.kind==FileLinkPacket.Kind.PROBE_RESULT){
                if(wire!=controlWire()||!host.lead()||probeSender||!phase.equals("measuring")){fail(new IOException("Unbound probe result"),true);return;}
                if(comparison==null)comparison=new FileRouteQualityPolicy.Sample[4];
                if(comparison[packet.number]!=null){fail(new IOException("Repeated link sample"),true);return;}
                comparison[packet.number]=new FileRouteQualityPolicy.Sample(packet.value,packet.nanos,packet.nanos<=FileRouteQualityPolicy.ROUND_NS);
                if(packet.number==3)decide();return;
            }
            if(!phase.equals("measuring")){
                if(fault)return;
                if(lease==null||!backupBound){fail(new IOException("Probe before file drain"),true);return;}
                startProbe();
            }
            if(probe!=null)probe.receive(packet,wire==backup);return;
        }
        switch(packet.kind){
            case INTERRUPTED:
                if(wire!=controlWire()){fail(new IOException("Unbound interrupted link"),true);return;}
                fault=true;retaining=false;arm(FileRouteQualityPolicy.RECOVERY_NS);if(probe!=null){probe.close();probe=null;}freeze();advance();break;
            case MEASURE:
                if(wire!=controlWire()||host.lead()||lease==null||!backupBound||fault){fail(new IOException("Unprepared comparison sender"),true);return;}startProbe();break;
            case ENDPOINTS:if(wire!=controlWire()||!host.lead())throw new IllegalStateException("Invalid migration LAN advertisement");dial(packet.endpoints);break;
            case BIND:
                if(wire!=backup||host.lead()||backupBound){fail(new IOException("Invalid standby LAN binding"),true);return;}
                backupBound=true;wire.sendFileLink(FileLinkPacket.control(FileLinkPacket.Kind.BOUND,nonce,host.generation(),0));backupReady();break;
            case BOUND:
                if(wire!=backup||!host.lead()||backupBound){fail(new IOException("Invalid standby LAN binding reply"),true);return;}
                backupBound=true;backupReady();break;
            case PAUSE:
                if(wire!=host.selected()||fault)return;freeze();break;
            case PAUSED:if(wire!=controlWire()){fail(new IOException("Pause agreement on wrong lane"),true);return;}peerPaused=true;advance();break;
            case SELECT:
                if(wire!=controlWire()||host.lead()||lease==null||!backupBound){fail(new IOException("Unprepared LAN selection"),true);return;}
                if(!fault){qualityResult="peer_selected_lan";lastReason="peer_selected_lan";}
                install();phase="selecting";send(FileLinkPacket.control(FileLinkPacket.Kind.SELECTED,nonce,host.generation(),0));break;
            case SELECTED:
                if(wire!=controlWire()||!host.lead()||!selecting){fail(new IOException("Unexpected LAN selection reply"),true);return;}
                install();send(FileLinkPacket.control(FileLinkPacket.Kind.RESUME,nonce,host.generation(),0));restore();break;
            case RESUME:
                if(wire!=controlWire()||host.lead()||host.selected()!=backup){fail(new IOException("Unselected LAN recovery"),true);return;}restore();break;
            case FAILED:if(wire==controlWire())candidateFailed(new IOException("Peer LAN unavailable"));else fail(new IOException("Migration failure on wrong lane"),true);break;
            case ABORT:
                if(wire!=controlWire()||fault){fail(new IOException("Peer stopped automatic recovery"),false);return;}
                // A retired standby lane may close before ABORT arrives on the independent control lane.
                if(!host.lead()){qualityResult="peer_retained_direct";lastReason="peer_retained_direct";}
                keepDirect(false);break;
            default:fail(new IOException("Unexpected migration phase record"),true);
        }
    }
    private void startProbe(){
        if(probe!=null)return;probeStartedAt=System.nanoTime();phase="measuring";String token=nonce;
        if(deadline!=null)deadline.cancel(false);arm(3_000_000_000L);
        probe=new FileLinkProbe(new FileLinkProbe.Host(){
            public boolean send(boolean lan,FileLinkPacket packet,BooleanSupplier live){FramedSession wire=lan?backup:host.selected();return wire!=null&&wire.sendFileLink(packet,live);}
            public ScheduledExecutorService timer(){return host.timer();}
            public void done(FileRouteQualityPolicy.Sample[] samples){
                if(!current(token)||fault)return;probeNanos=Math.max(0,System.nanoTime()-probeStartedAt);
                if(probeSender){if(host.lead()){comparison=samples;decide();}else for(int i=0;i<4;i++){FileRouteQualityPolicy.Sample sample=samples[i];sendResult(i,sample);}}
            }
            public void failed(IOException error){trace("probe_error:"+error.getMessage());if(current(token)){if(fault)advance();else{qualityResult="inconclusive";keepDirect(host.lead());}}}
        },token,host.generation(),probeSender);probe.start();host.changed();
    }
    private void sendResult(int number,FileRouteQualityPolicy.Sample sample){send(FileLinkPacket.result(nonce,host.generation(),number,sample==null?0:sample.bytes,sample==null?0:Math.min(FileRouteQualityPolicy.STALL_NS,sample.nanos)));}
    private void decide(){
        if(!host.lead()||fault||comparison==null)return;
        FileRouteQualityPolicy.Sample[] direct={comparison[0],comparison[2]},lan={comparison[1],comparison[3]};
        long cost=comparisonCost+Math.max(0,System.nanoTime()-comparisonStarted);
        assessmentNanos=Math.max(0,System.nanoTime()-comparisonStarted);
        probeNanos=probeStartedAt==0?0:Math.max(0,System.nanoTime()-probeStartedAt);
        decisionCost=cost;
        qualityDecision=FileRouteQualityPolicy.evaluate(direct,lan,comparisonRemaining,cost,comparisonCapacity);
        StringBuilder samples=new StringBuilder();
        for(int i=0;i<comparison.length;i++){
            if(i>0)samples.append(';');var sample=comparison[i];
            samples.append(i%2==0?"direct":"lan").append(i/2+1).append(':');
            if(sample==null)samples.append("missing");else samples.append(sample.bytes).append(',').append(sample.nanos).append(',').append(sample.valid());
        }
        qualitySamples=samples.toString();
        if(qualityDecision.preferLan()){
            qualityResult="lan_faster";lastReason="lan_measurably_faster";selecting=true;phase="selecting";send(FileLinkPacket.control(FileLinkPacket.Kind.SELECT,nonce,host.generation(),0));
        }else{qualityResult="keep_direct";keepDirect(true);}
    }
    private void keepDirect(boolean notify){trace("retain");
        if(fault){advance();return;}
        boolean first=!retaining;retaining=true;phase="retaining";
        if(first){
            if(qualityResult.equals("not_measured")||qualityResult.equals("comparing"))qualityResult="inconclusive";
            lastReason=qualityResult.equals("inconclusive")?"comparison_inconclusive":host.lead()?"direct_retained":"peer_retained_direct";
        }
        if(probe!=null){probe.close();probe=null;}
        if(notify&&first)send(FileLinkPacket.control(FileLinkPacket.Kind.ABORT,nonce,host.generation(),0));
        disposeBackup();
        if(lease==null)drain();
        advance();host.changed();
    }
    private void install(){
        if(!host.route().equals("lan"))switches++;recoveries++;
        host.install(backup,nonce,fault,bluetoothFallback?"bluetooth":"lan");binding=new Binding(nonce,backup);
        if(deadline!=null){deadline.cancel(false);deadline=null;}
    }
    private void restore(){
        if(phase.equals("restoring"))return;phase="restoring";String token=nonce;FileRouteResume restoring=lease;
        host.files().resumeAfterRouteChange(restoring).whenComplete((value,error)->host.post(()->{
            if(!current(token))return;
            if(error!=null){fail(asIo(error),false);return;}
            if(pauseRequestedAt!=0){assessmentNanos=Math.max(0,System.nanoTime()-comparisonStarted);pauseNanos=Math.max(0,System.nanoTime()-pauseRequestedAt);}
            Binding snapshot=binding;if(snapshot!=null)snapshot.expires=System.nanoTime()+FileRouteQualityPolicy.RECOVERY_NS;retire(token);phase="idle";nonce=null;lease=null;freezing=false;selecting=false;
            if(ready!=null&&!ready.isDone())ready.complete(host.route().equals("wifi-direct")?AutomaticFileRoutes.Route.WIFI_DIRECT:host.route().equals("bluetooth")?AutomaticFileRoutes.Route.BLUETOOTH:AutomaticFileRoutes.Route.LAN);host.changed();
        }));
    }
    boolean sendRecoveryOffer(TransferPacket offer){
        Binding snapshot=binding;
        if(snapshot==null||!supported()||!host.valid()||snapshot.wire!=host.selected()||System.nanoTime()>snapshot.expires)return false;
        try{return snapshot.wire.sendFileLink(FileLinkPacket.recoveryOffer(snapshot.nonce,host.generation(),offer));}catch(IOException|IllegalArgumentException invalid){return false;}
    }
    private void receiveOffer(FileLinkPacket packet,FramedSession wire){
        Binding snapshot=binding;
        if(snapshot==null||wire!=host.selected()||wire!=snapshot.wire||!Objects.equals(snapshot.nonce,packet.nonce)||System.nanoTime()>snapshot.expires){if(retired.contains(packet.nonce))return;fail(new IOException("Recovery offer outside selected migration"),true);return;}
        try{host.files().receiveRecoveryOffer(TransferCodec.decode(packet.data,0,packet.data.length));}
        catch(IOException error){fail(error,true);}
    }
    private void candidateFailed(IOException error){
        if(!busy())return;
        disposeBackup();backupBound=false;dialing=false;
        if(!fault){keepDirect(host.lead());return;}
        if(!host.lead()){send(FileLinkPacket.control(FileLinkPacket.Kind.FAILED,nonce,host.generation(),0));return;}
        if(attempts>=3){if(!host.parentLost()&&host.parentRoute().equals("bluetooth")&&host.parent().isReady()){fallbackBluetooth(true);return;}fail(error,false);return;}
        phase="preparing";String token=nonce;
        if(retry!=null)retry.cancel(false);retry=host.timer().schedule(()->{if(current(token)){send(FileLinkPacket.prepare(nonce,host.generation(),1,host.routeToken()));ensureBackup();}},Math.max(1,attempts),TimeUnit.SECONDS);
    }
    private void fallbackBluetooth(boolean notify){
        if(bluetoothFallback)return;
        bluetoothFallback=true;disposeBackup();backup=host.parent();backup.enableFileLink();backupBound=true;
        peerPaused=false;lastReason="lan_unavailable_bluetooth";
        if(notify)send(FileLinkPacket.prepare(nonce,host.generation(),3,host.routeToken()));
        if(lease!=null)send(FileLinkPacket.control(FileLinkPacket.Kind.PAUSED,nonce,host.generation(),0));else freeze();
        advance();
    }
    private boolean send(FileLinkPacket packet){
        FramedSession control=controlWire();if(control==null&&host.parentLost())return true;
        boolean accepted=control!=null&&control.sendFileLink(packet);
        if(!accepted&&busy()){
            if(!host.parent().isReady()&&host.recoverParent())return false;
            if(!retaining)fail(new IOException("Migration control unavailable"),false);
        }
        return accepted;
    }
    private boolean current(String token){return !closed&&busy()&&host.valid()&&Objects.equals(nonce,token);}
    private void retire(String token){if(token==null)return;retired.add(token);while(retired.size()>128)retired.remove(retired.iterator().next());}
    private void disposeBackup(){candidateEpoch++;cancelCandidateBudget();dialing=false;FramedSession old=backup;backup=null;if(old!=null&&old!=host.parent()&&old!=host.selected())old.close(UiText.EMPTY);}
    private void fail(IOException error,boolean security){trace("failure");
        if(closed)return;if(!security&&busy()&&!fault){qualityResult="inconclusive";keepDirect(host.lead());return;}stop(error);host.failure(error,security);
    }
    void stop(IOException error){trace("stop");binding=null;
        clearQualityRequest("stopped");
        if(deadline!=null){deadline.cancel(false);deadline=null;}if(retry!=null){retry.cancel(false);retry=null;}
        if(probe!=null){probe.close();probe=null;}retire(nonce);nonce=null;phase="failed";disposeBackup();
        if(ready!=null&&!ready.isDone())ready.completeExceptionally(error);host.changed();
    }
    TransferPath activityPath(){return fault?TransferPath.RECOVERING_LAN:phase.equals("measuring")?TransferPath.COMPARING:TransferPath.PENDING_LAN;}
    private String qualityDiagnostics(){
        var decision=qualityDecision;
        return "file_quality_diagnostics_schema=2\nfile_quality_scope=last_assessment\n"
            +"file_quality_decision_available="+(decision!=null)+"\nfile_quality_decision_owner=coordinator\nfile_quality_local_role="+(host.lead()?"coordinator":"peer")+"\n"
            +"file_quality_required_margin_percent=25\nfile_quality_required_net_savings_ms=2000\nfile_quality_decision_cost_ms="+decisionCost/1_000_000+"\n"
            +"file_quality_decision_reason="+(decision==null?qualityResult.startsWith("peer_")?"peer_decision_not_shared":qualityAdmission:decision.reason())+"\n"
            +"file_quality_direct_bytes_per_second="+(decision==null?0:Math.round(decision.directBytesPerSecond()))+"\n"
            +"file_quality_lan_bytes_per_second="+(decision==null?0:Math.round(decision.lanBytesPerSecond()))+"\n"
            +"file_quality_net_savings_ms="+(decision==null?0:Math.round(decision.netSavingsSeconds()*1000))+"\n"
            +"file_quality_remaining_bytes="+comparisonRemaining+"\nfile_quality_io_capacity_bytes_per_second="+comparisonCapacity+"\n"
            +"file_quality_switch_estimate_ms="+comparisonCost/1_000_000+"\nfile_quality_assessment_ms="+assessmentNanos/1_000_000+"\n"
            +"file_quality_setup_ms="+setupNanos/1_000_000+"\nfile_quality_probe_ms="+probeNanos/1_000_000+"\n"
            +"file_quality_quiesce_to_resume_ms="+pauseNanos/1_000_000+"\n"
            +"file_quality_samples_bytes_ns_complete="+qualitySamples+"\n";
    }
    String diagnostics(){return qualityDiagnostics()+"file_handover_supported="+supported()+"\nfile_recovery_lan_known="+!peerEndpoints.isEmpty()+"\nfile_parent_recovery="+host.parentLost()+"\nfile_quality_result="+qualityResult+"\nfile_quality_request_state="+qualityRequestState+"\nfile_quality_request_count="+qualityRequests+"\nfile_quality_admission="+qualityAdmission+"\nfile_handover_phase="+phase+"\nfile_handover_trace="+trace()+"\nfile_switch_count="+switches+"\nfile_recovery_count="+recoveries+"\nfile_switch_reason="+lastReason+"\n";}
    public void close(){if(closed)return;stop(new IOException("FILE migration closed"));closed=true;if(monitor!=null)monitor.cancel(false);phase="closed";binding=null;}
    private final class Lane implements FramedSession.Listener {
        final String token;final boolean initiator;FramedSession wire;
        Lane(String token,boolean initiator){this.token=token;this.initiator=initiator;}
        boolean live(){return host.valid()&&(wire==backup&&current(token)||wire==host.selected());}
        public void onHello(Frame frame){host.post(()->{if(!live()){wire.close(UiText.EMPTY);return;}if(!host.expectedPeer(wire,frame)){fail(new IOException("LAN recovery identity mismatch"),true);return;}wire.approve();});}
        public void onReady(){host.post(()->{if(!live())return;if(!wire.automaticFilesSupported()){candidateFailed(new IOException("LAN recovery extension unsupported"));return;}if(initiator)wire.sendFileLink(FileLinkPacket.control(FileLinkPacket.Kind.BIND,token,host.generation(),0));});}
        public void onFileLink(FileLinkPacket packet){host.post(()->{if(live())receive(packet,wire);});}
        public void onTransfer(TransferPacket packet){if(host.valid()&&wire==host.selected()&&host.generation().equals(packet.connectionGeneration))host.transfer(wire,token,packet);else host.post(()->{if(live())fail(new IOException("FILE data before LAN selection"),true);});}
        public void onFileRoute(FileRoutePacket packet){host.post(()->{if(live())fail(new IOException("Legacy route control on standby LAN"),true);});}
        public void onText(Frame frame){host.post(()->fail(new IOException("Text on recovery FILE lane"),true));}
        public void onAck(String id){host.post(()->fail(new IOException("Chat receipt on recovery FILE lane"),true));}
        public void onFailure(IOException error){if(error instanceof NoiseRecordChannel.AuthenticationException)host.post(()->{if(live())fail(error,true);});}
        public void onClosed(UiText reason){host.post(()->{if(!live())return;if(wire==host.selected()){if(!interrupted())fail(new IOException("LAN FILE interrupted"),false);}else candidateFailed(new IOException("Standby LAN interrupted"));});}
    }
    private static IOException asIo(Throwable error){return error instanceof IOException?(IOException)error:new IOException("Automatic FILE recovery failed",error);}
    private static void dispose(StreamConnection connection){try{connection.close();}catch(IOException ignored){}}
}
