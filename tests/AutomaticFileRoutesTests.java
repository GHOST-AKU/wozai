package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import dev.ghost.nearbyim.i18n.UiText;
public final class AutomaticFileRoutesTests {
    static int checks;
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;}
    public static void main(String[] args)throws Exception {
        check(hasApi(),"Authenticated automatic FILE route API is missing");
        codec();
        for(boolean reverse:new boolean[]{false,true})try(Pair p=new Pair("bluetooth",true,false)) {p.a.platform.reverse=p.b.platform.reverse=reverse;p.a.platform.directSuccess=p.b.platform.directSuccess=true;
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Direct selected independent of chat");p.transfer();
        }
        try(Pair p=new Pair("bluetooth",false,true,true)) {try{p.a.files.ensureReady().get(5,TimeUnit.SECONDS);throw new AssertionError("Wrong root accepted");}catch(ExecutionException good){checks++;}
            check(p.a.files.route().isEmpty()&&p.a.wire.isReady(),"Identity mismatch is terminal for FILE only");
            check(p.a.files.ensureReady().isCompletedExceptionally(),"Security mismatch cannot fallback or retry");
        }
        try(Pair p=new Pair("bluetooth",true,false)) {p.a.platform.delayDirect=true;CompletableFuture<AutomaticFileRoutes.Route> f=p.a.files.ensureReady();
            check(p.a.platform.directCalled.await(3,TimeUnit.SECONDS),"Delayed Direct called");p.a.files.abort();try{f.get(3,TimeUnit.SECONDS);throw new AssertionError("Abort ready");}catch(ExecutionException good){checks++;}
            ClosedConnection connection=new ClosedConnection();p.a.platform.opened.accept(new AutomaticFileRoutes.Opened(connection,false));check(connection.closed.await(3,TimeUnit.SECONDS),"Late Direct callback disposed");check(p.a.wire.isReady(),"Abort retains chat");
        }
        try(Pair p=new Pair("bluetooth",false,false)) {check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"BT fallback");check(p.b.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"Peer BT selected");p.transfer();}
        try(Pair p=new Pair("lan",true,false)) {check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Direct failure falls back to parent LAN");p.transfer();}
        try(Pair p=new Pair("bluetooth",true,true)) {CompletableFuture<AutomaticFileRoutes.Route> a=p.a.files.ensureReady(),b=p.b.files.ensureReady();check(a.get(8,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN&&b.get(8,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Simultaneous FILE requests select one extra LAN");check(p.a.platform.dials+p.b.platform.dials==1,"Only deterministic leader dials");p.transfer();check(p.a.wire.isReady()&&p.b.wire.isReady(),"Chat survives independent file lane");}
        try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.delaySelectionAck=true;CompletableFuture<AutomaticFileRoutes.Route> a=p.a.files.ensureReady(),b=p.b.files.ensureReady();b.get(5,TimeUnit.SECONDS);
            TransferPacket packet=TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.b.wire.connectionGeneration(),1,0,new byte[]{9});
            check(p.b.files.send(packet),"Follower sends immediately after selection");check(p.a.received.poll(5,TimeUnit.SECONDS)!=null,"FILE data cannot race parent selection acknowledgment");a.get(5,TimeUnit.SECONDS);
        }
        try(Pair p=new Pair("bluetooth",false,false)) {
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"Initial BT fallback");
            p.a.platform.lan=p.b.platform.lan=true;
            check(p.a.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Next idle batch rechecks LAN");p.transfer();
            p.a.platform.direct=p.b.platform.direct=true;p.a.platform.directSuccess=p.b.platform.directSuccess=true;
            p.b.busy=true;check(p.a.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Remote active FILE preserves lane");p.b.busy=false;
            check(p.b.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.WIFI_DIRECT,"Permission grant rechecks Direct from follower");p.transfer();
        }
        try(Pair p=new Pair("bluetooth",false,false,false,false)) {
            TransferPacket packet=TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.a.wire.connectionGeneration(),1,0,new byte[]{2});check(p.a.wire.sendTransfer(packet),"Legacy encrypted peer sends without FILE control");check(p.b.received.poll(3,TimeUnit.SECONDS)!=null&&p.b.files.route().equals("bluetooth"),"Legacy inbound FILE selects actual chat transport");
        }
        for(boolean wrongGeneration:new boolean[]{false,true})try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            java.lang.reflect.Field field=AutomaticFileRoutes.class.getDeclaredField("candidate");field.setAccessible(true);FramedSession lane=(FramedSession)field.get(p.b.files);
            java.lang.reflect.Field token=AutomaticFileRoutes.class.getDeclaredField("nonce");token.setAccessible(true);String nonce=(String)token.get(p.b.files);
            FileRoutePacket attack=new FileRoutePacket(FileRoutePacket.Kind.BIND,wrongGeneration?nonce:UUID.randomUUID().toString(),wrongGeneration?repeat('e',64):p.b.wire.connectionGeneration(),AutomaticFileRoutes.Route.LAN,false,Collections.emptyList());
            check(lane.sendFileRoute(attack),"Authenticated malformed lane binding sent");check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.a.files.ensureReady().isCompletedExceptionally(),"Generation/nonce mismatch is terminal");check(p.a.wire.isReady(),"Binding rejection leaves chat healthy");
        }
        for(boolean header:new boolean[]{false,true})try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.platform.corruptHeader=header;p.b.platform.corrupt.set(true);
            check(p.b.files.send(TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.b.wire.connectionGeneration(),1,0,new byte[]{9})),"Tampered encrypted record transmitted");
            IOException error=p.a.errors.poll(3,TimeUnit.SECONDS);check(error instanceof dev.ghost.nearbyim.noise.NoiseRecordChannel.AuthenticationException,"Ciphertext tamper has typed security failure");check(p.a.files.ensureReady().isCompletedExceptionally()&&p.a.wire.isReady(),"Ciphertext failure never falls back or closes chat");
        }
        for(int type:new int[]{Frame.TEXT,Frame.ACK,Frame.AVATAR})try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            java.lang.reflect.Field candidate=AutomaticFileRoutes.class.getDeclaredField("candidate");candidate.setAccessible(true);FramedSession lane=(FramedSession)candidate.get(p.b.files);java.lang.reflect.Field noise=FramedSession.class.getDeclaredField("noise");noise.setAccessible(true);
            ((dev.ghost.nearbyim.noise.NoiseRecordChannel)noise.get(lane)).write(type==Frame.AVATAR?ProtocolV4.encodeAvatar(AvatarPacket.profile(AvatarProfile.defaults(1))):ProtocolV4.encode(new Frame(type,UUID.randomUUID().toString(),type==Frame.TEXT?"wrong lane":"",1)));
            check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.a.files.ensureReady().isCompletedExceptionally(),"Chat TEXT/ACK are rejected on FILE lane");check(p.a.wire.isReady(),"Wrong lane record does not close chat");
        }
        try(Pair p=new Pair("bluetooth",false,true)) {p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.a.approved=false;
            check(!p.a.files.send(TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.a.wire.connectionGeneration(),1,0,new byte[]{1})),"Revocation immediately gates FILE send");check(p.a.files.ensureReady().isCompletedExceptionally(),"Revocation gates resume");check(p.a.errors.poll(2,TimeUnit.SECONDS)!=null,"Revocation tears down file route");
        }
        try(Pair p=new Pair("bluetooth",true,false)) {p.a.platform.delayDirect=true;CompletableFuture<AutomaticFileRoutes.Route> f=p.a.files.ensureReady();check(p.a.platform.directCalled.await(3,TimeUnit.SECONDS),"Pending Direct started");p.a.files.close();check(f.isCompletedExceptionally(),"Close cancels pending readiness");ClosedConnection late=new ClosedConnection();p.a.platform.opened.accept(new AutomaticFileRoutes.Opened(late,false));check(late.closed.await(2,TimeUnit.SECONDS),"Close disposes late native socket");}
        try(Pair p=new Pair("bluetooth",false,false)) {p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.a.files.abort();check(p.b.errors.poll(2,TimeUnit.SECONDS)!=null,"Abort coordinates reused CHAT file lane");check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"Explicit retry after cancellation negotiates again");p.transfer();}
        try(Pair p=new Pair("lan",false,false)) {
            String token=UUID.randomUUID().toString();FileRoutePacket lie=new FileRoutePacket(FileRoutePacket.Kind.BEGIN,token,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.BLUETOOTH,false,Collections.emptyList());
            check(p.a.wire.sendFileRoute(lie),"Authenticated false parent fallback sent");check(p.a.wire.sendFileRoute(new FileRoutePacket(FileRoutePacket.Kind.SELECT,token,p.a.wire.connectionGeneration(),AutomaticFileRoutes.Route.BLUETOOTH,false,Collections.emptyList())),"False fallback selection sent");check(p.b.errors.poll(2,TimeUnit.SECONDS)!=null&&p.b.files.route().isEmpty(),"Parent LAN cannot be labeled Bluetooth by peer control");check(p.b.wire.isReady(),"False provenance rejection preserves chat");
        }
        retiredLaneCallbacks();liveLaneRejections();failurePromiseRetirement();retiredParentPacket();invalidParentPacket();retiredParentCaps();retiredParentAcrossUpgrade();
        System.out.println("Automatic file routes: "+checks+" checks passed");
    }
    static Object field(Object owner,String name)throws Exception {java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    static FramedSession.Listener laneListener(AutomaticFileRoutes routes)throws Exception {return (FramedSession.Listener)field(field(routes,"candidate"),"listener");}
    static void drainActor(AutomaticFileRoutes routes)throws Exception {((ExecutorService)field(routes,"actor")).submit(()->{}).get(3,TimeUnit.SECONDS);}
    static TransferPacket packet(String generation)throws IOException {return TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),generation,1,0,new byte[]{7});}
    static void retiredLaneCallbacks()throws Exception {
        for(boolean beforeSelection:new boolean[]{true,false})try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);FramedSession.Listener retired=laneListener(p.a.files);
            p.a.files.abort();check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Real lane abort observed on both peers");p.a.errors.clear();p.b.errors.clear();
            if(beforeSelection){p.a.platform.holdLan=true;p.a.platform.connectEntered=new CountDownLatch(1);}
            CompletableFuture<AutomaticFileRoutes.Route> replacement=p.a.files.ensureReady();if(beforeSelection){check(p.a.platform.connectEntered.await(3,TimeUnit.SECONDS)&&!replacement.isDone(),"Replacement socket provider held before selection");}else replacement.get(5,TimeUnit.SECONDS);
            retired.onTransfer(packet(p.a.wire.connectionGeneration()));retired.onText(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"retired",1));retired.onAck(UUID.randomUUID().toString());retired.onFailure(new dev.ghost.nearbyim.noise.NoiseRecordChannel.AuthenticationException("retired proof"));
            drainActor(p.a.files);check(p.a.errors.isEmpty(),"Retired DATA/TEXT/ACK/auth callbacks cannot fail replacement FILE route");
            if(beforeSelection)p.a.platform.releaseLan();
            check(replacement.get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Retired callback cannot poison explicit resume");check(p.a.received.isEmpty(),"Retired DATA is not delivered to engine");p.transfer();check(p.a.wire.isReady()&&p.b.wire.isReady(),"Retired callbacks retain healthy chat");
        }
    }
    static void liveLaneRejections()throws Exception {
        for(boolean beforeSelection:new boolean[]{true,false})try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);FramedSession.Listener live=laneListener(p.a.files);
            if(beforeSelection){java.lang.reflect.Field selected=AutomaticFileRoutes.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(p.a.files,null);}
            live.onTransfer(packet(beforeSelection?p.a.wire.connectionGeneration():repeat('d',64)));drainActor(p.a.files);
            check(!p.a.errors.isEmpty()&&p.a.files.ensureReady().isCompletedExceptionally(),"Current lane DATA before selection or wrong generation stays terminal");check(p.a.received.isEmpty()&&p.a.wire.isReady(),"Invalid current lane DATA reaches no engine and leaves chat healthy");
        }
    }
    static void failurePromiseRetirement()throws Exception {
        try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            CountDownLatch entered=new CountDownLatch(1);p.a.platform.stopEntered=entered;p.a.platform.stopRelease=new CountDownLatch(1);p.a.files.abort();
            check(entered.await(3,TimeUnit.SECONDS),"Old failure paused inside platform teardown");
            CompletableFuture<AutomaticFileRoutes.Route> replacement=p.a.files.ensureReady();check(!replacement.isDone(),"Replacement readiness admitted during old teardown");
            p.a.platform.stopRelease.countDown();check(replacement.get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Old cancellation cannot reject newly allocated readiness promise");p.transfer();
        }
    }
    static void retiredParentPacket()throws Exception {
        try(Pair p=new Pair("bluetooth",false,false)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            p.a.parentDecoded=new CountDownLatch(1);p.a.releaseParent=new CountDownLatch(1);p.a.parentDispatched=new CountDownLatch(1);
            check(p.b.files.send(packet(p.b.wire.connectionGeneration())),"Authorized parent FILE packet sent");check(p.a.parentDecoded.await(3,TimeUnit.SECONDS),"Real Noise parent DATA decoded before cancellation");
            p.a.files.abort();check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Shared parent FILE canceled while decoded DATA callback held");p.a.errors.clear();p.b.errors.clear();
            p.a.releaseParent.countDown();check(p.a.parentDispatched.await(3,TimeUnit.SECONDS),"Held parent callback dispatched after FILE retirement");drainActor(p.a.files);
            check(p.a.errors.isEmpty(),"Already-decoded retired parent DATA cannot terminally poison FILE resume");check(p.a.received.isEmpty(),"Retired parent DATA is never delivered while unselected");check(p.a.wire.isReady()&&p.b.wire.isReady(),"Shared parent retirement keeps chat healthy");
            check(p.a.files.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"Explicit shared parent resume remains available");p.transfer();
        }
    }
    static void invalidParentPacket()throws Exception {
        for(boolean retired:new boolean[]{false,true})try(Pair p=new Pair("bluetooth",false,false)) {
            if(retired){p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);p.a.files.abort();check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Parent authorization retired before wrong-generation check");}
            check(p.b.wire.sendTransfer(packet(retired?repeat('c',64):p.b.wire.connectionGeneration())),"Invalid parent traffic transmitted through real Noise");
            check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.a.files.ensureReady().isCompletedExceptionally(),"Never-selected parent traffic and retired wrong-generation traffic remain terminal");check(p.a.received.isEmpty()&&p.a.wire.isReady(),"Invalid parent traffic reaches no engine and leaves CHAT healthy");
        }
    }
    static void sendParent(Pair p,TransferPacket packet)throws Exception {
        CountDownLatch dispatched=new CountDownLatch(1);p.a.parentDispatched=dispatched;
        if(!p.b.wire.sendTransfer(packet)||!dispatched.await(3,TimeUnit.SECONDS))throw new AssertionError("Real parent record was not dispatched");
    }
    static void retireParent(Pair p)throws Exception {
        p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);p.a.files.abort();
        check(p.a.errors.poll(3,TimeUnit.SECONDS)!=null&&p.b.errors.poll(3,TimeUnit.SECONDS)!=null,"Parent selected authorization retired for finite drain");p.a.errors.clear();p.b.errors.clear();
    }
    static void retiredParentCaps()throws Exception {
        for(boolean data:new boolean[]{true,false})try(Pair p=new Pair("bluetooth",false,false)) {
            retireParent(p);
            if(data){for(long remaining=FairRecordWriter.FILE_BUDGET;remaining>0;){int length=(int)Math.min(TransferLimits.DATA_BYTES,remaining);sendParent(p,TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.b.wire.connectionGeneration(),length,0,new byte[length]));remaining-=length;}}
            else{TransferPacket control=TransferPacket.control(TransferPacket.Kind.CANCEL,UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.b.wire.connectionGeneration(),true,1,0,0,null,"canceled");for(int i=0;i<FairRecordWriter.MAX_FILE_ENTRIES+1;i++)sendParent(p,control);}
            drainActor(p.a.files);check(p.a.errors.isEmpty()&&p.a.received.isEmpty(),"Retired parent allowance discards its complete bounded budget");
            sendParent(p,data?packet(p.b.wire.connectionGeneration()):TransferPacket.control(TransferPacket.Kind.CANCEL,UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.b.wire.connectionGeneration(),true,1,0,0,null,"canceled"));drainActor(p.a.files);
            check(!p.a.errors.isEmpty()&&p.a.files.ensureReady().isCompletedExceptionally(),"Retired parent byte/control allowance cannot be exceeded");check(p.a.received.isEmpty()&&p.a.wire.isReady(),"Excess retired traffic reaches no engine and leaves chat healthy");
        }
    }
    static void retiredParentAcrossUpgrade()throws Exception {
        try(Pair p=new Pair("bluetooth",false,false)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.a.platform.lan=p.b.platform.lan=true;check(p.a.files.prepareNewBatch().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.LAN,"Historical parent authorization retired for idle LAN upgrade");p.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            sendParent(p,packet(p.b.wire.connectionGeneration()));drainActor(p.a.files);check(p.a.errors.isEmpty()&&p.a.received.isEmpty()&&p.a.files.route().equals("lan"),"Late authorized parent DATA stays discarded after extra lane selection");p.transfer();
        }
        try(Pair p=new Pair("bluetooth",false,true)) {
            p.a.files.ensureReady().get(5,TimeUnit.SECONDS);p.b.files.ensureReady().get(5,TimeUnit.SECONDS);sendParent(p,packet(p.b.wire.connectionGeneration()));drainActor(p.a.files);
            check(!p.a.errors.isEmpty()&&p.a.files.ensureReady().isCompletedExceptionally()&&p.a.received.isEmpty(),"Extra lane cannot grant never-selected parent DATA authority");
        }
    }
    static boolean hasApi(){try{Class.forName("dev.ghost.nearbyim.core.AutomaticFileRoutes");return true;}catch(ClassNotFoundException e){return false;}}
    static void codec()throws Exception {
        FileRoutePacket packet=new FileRoutePacket(FileRoutePacket.Kind.BEGIN,UUID.randomUUID().toString(),repeat('a',64),AutomaticFileRoutes.Route.LAN,false,Collections.emptyList());
        check(ProtocolV4.decodeRecord(ProtocolV4.encodeFileRoute(packet)).fileRoute.kind==FileRoutePacket.Kind.BEGIN,"FILE control roundtrip");
        try{new AutomaticFileRoutes.Endpoint("example.org",1234);throw new AssertionError("DNS endpoint accepted");}catch(IllegalArgumentException good){checks++;}
        try{new AutomaticFileRoutes.Endpoint("8.8.8.8",1234);throw new AssertionError("Public endpoint accepted");}catch(IllegalArgumentException good){checks++;}
    }
    static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    static byte[] key(){byte[] k=new byte[32];new java.security.SecureRandom().nextBytes(k);return k;}
    static StreamConnection[] sockets()throws Exception {try(ServerSocket s=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){Socket a=new Socket(InetAddress.getLoopbackAddress(),s.getLocalPort()),b=s.accept();return new StreamConnection[]{SessionTests.connection(a),SessionTests.connection(b)};}}
    static final class TestPlatform implements AutomaticFileRoutes.Platform {
        volatile String attemptNonce;volatile Throwable directFailure;
        boolean direct,lan;TestPlatform peer;Consumer<StreamConnection> accept;Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints;int dials;boolean directSuccess,delayDirect,reverse,corruptHeader;volatile boolean holdLan;volatile Runnable heldLan;volatile StreamConnection[] heldConnections;CountDownLatch connectEntered;volatile CountDownLatch stopEntered,stopRelease;final java.util.concurrent.atomic.AtomicBoolean corrupt=new java.util.concurrent.atomic.AtomicBoolean();Consumer<AutomaticFileRoutes.Opened> opened;boolean dialer;final CountDownLatch directCalled=new CountDownLatch(1);
        TestPlatform(boolean direct,boolean lan){this.direct=direct;this.lan=lan;}
        public boolean directSupported(){return direct;}
        public void prepareLan(Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints,Consumer<StreamConnection> accepted){accept=accepted;this.endpoints=endpoints;CompletableFuture.delayedExecutor(40,TimeUnit.MILLISECONDS).execute(this::publish);}
        public List<AutomaticFileRoutes.Endpoint> currentLanEndpoints(){return lan?List.of(new AutomaticFileRoutes.Endpoint("192.168.1.2",2345)):List.of();}
        void publish(){endpoints.accept(lan?Collections.singletonList(new AutomaticFileRoutes.Endpoint("192.168.1.2",2345)):Collections.emptyList());}
        public void connectLan(List<AutomaticFileRoutes.Endpoint> endpoints,Consumer<StreamConnection> opened,Consumer<Throwable> failed){dials++;try{StreamConnection[] pair=sockets();Runnable deliver=()->{peer.accept.accept(peer.wrap(pair[1]));opened.accept(wrap(pair[0]));};if(holdLan){heldConnections=pair;heldLan=deliver;connectEntered.countDown();}else deliver.run();}catch(Exception e){failed.accept(e);}}
        public void openDirect(String attempt,boolean dialer,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){attemptNonce=attempt;openDirect(dialer,opened,failed);}
        public void openDirect(boolean dialer,Consumer<AutomaticFileRoutes.Opened> opened,Consumer<Throwable> failed){this.opened=opened;this.dialer=dialer;directCalled.countDown();if(delayDirect)return;if(!directSuccess){failed.accept(directFailure==null?new IOException("Direct unavailable"):directFailure);return;}synchronized(TestPlatform.class){if(peer.opened!=null&&peer.directSuccess)try{StreamConnection[] connections=sockets();opened.accept(new AutomaticFileRoutes.Opened(connections[0],reverse?dialer:!dialer));peer.opened.accept(new AutomaticFileRoutes.Opened(connections[1],reverse?!dialer:dialer));}catch(Exception e){failed.accept(e);}}}
        StreamConnection wrap(StreamConnection base){return new StreamConnection(){public InputStream input()throws IOException{return base.input();}public String label(){return base.label();}public void close()throws IOException{base.close();}public OutputStream output()throws IOException{OutputStream out=base.output();return new OutputStream(){public void write(int value)throws IOException{out.write(value);}public void flush()throws IOException{out.flush();}public void write(byte[] bytes,int off,int len)throws IOException{if(corrupt.compareAndSet(true,false)){byte[] changed=Arrays.copyOfRange(bytes,off,off+len);changed[corruptHeader?4:changed.length-1]^=1;out.write(changed);}else out.write(bytes,off,len);}};}};}
        void releaseLan(){Runnable deliver=heldLan;heldLan=null;heldConnections=null;holdLan=false;deliver.run();}
        public void stopDirect(){CountDownLatch entered=stopEntered,release=stopRelease;if(entered!=null&&release!=null){stopEntered=null;entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Teardown barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
        public void close(){CountDownLatch release=stopRelease;if(release!=null)release.countDown();StreamConnection[] connections=heldConnections;if(connections!=null)for(StreamConnection connection:connections)try{connection.close();}catch(IOException ignored){}}

    }
    static final class ClosedConnection implements StreamConnection {
        final CountDownLatch closed=new CountDownLatch(1);
        public InputStream input(){return new ByteArrayInputStream(new byte[0]);}public OutputStream output(){return new ByteArrayOutputStream();}public String label(){return "late";}public void close(){closed.countDown();}
    }
    static final class End implements FramedSession.Listener,AutomaticFileRoutes.Listener {
        volatile SocketPath nativePath;boolean ignorePath;
        FramedSession wire;AutomaticFileRoutes files;TestPlatform platform;boolean delaySelectionAck,busy,approved=true;volatile CountDownLatch parentDecoded,releaseParent,parentDispatched;final CountDownLatch ready=new CountDownLatch(1);final BlockingQueue<TransferPacket> received=new LinkedBlockingQueue<>();final BlockingQueue<IOException> errors=new LinkedBlockingQueue<>();
        public void onHello(Frame f){wire.approve();}public void onReady(){ready.countDown();}public void onClosed(UiText t){}public void onText(Frame f){}public void onAck(String id){}
        public void onFileRoute(FileRoutePacket p){if(ignorePath&&UUID.fromString(p.nonce).version()==3)return;if(delaySelectionAck&&p.kind==FileRoutePacket.Kind.SELECT_ACK)try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}files.receiveControl(p);}public void onTransfer(TransferPacket p){CountDownLatch decoded=parentDecoded,release=releaseParent,dispatched=parentDispatched;if(decoded!=null){parentDecoded=null;decoded.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("Parent callback barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();}}files.receiveChatTransfer(p);if(dispatched!=null)dispatched.countDown();}
        public boolean busy(){return busy;}public void receive(TransferPacket p){received.add(p);}public void changed(){}public void failed(IOException e){errors.add(e);}
    }
    static final class Pair implements AutoCloseable {
        final End a=new End(),b=new End();
        Pair(String route,boolean direct,boolean lan)throws Exception {this(route,direct,lan,false);}
        Pair(String route,boolean direct,boolean lan,boolean wrongRoot)throws Exception {this(route,direct,lan,wrongRoot,true);}
        Pair(String route,boolean direct,boolean lan,boolean wrongRoot,boolean automatic)throws Exception {
            this(route,direct,lan,wrongRoot,automatic,null,null);
        }
        Pair(String route,boolean direct,boolean lan,boolean wrongRoot,boolean automatic,SocketPath aPath,SocketPath bPath)throws Exception {
            DeviceIdentity ka=DeviceIdentity.generate(),kb=DeviceIdentity.generate();byte[] na=key(),nb=key();StreamConnection[] pair=sockets();
            a.nativePath=aPath;b.nativePath=bPath;
            a.wire=FramedSession.secure(path(pair[0],a),CoreTests.A,"A",ka,na,true,a,false,automatic);b.wire=FramedSession.secure(path(pair[1],b),CoreTests.B,"B",kb,nb,false,b,false,automatic);
            a.wire.start();b.wire.start();check(a.ready.await(5,TimeUnit.SECONDS)&&b.ready.await(5,TimeUnit.SECONDS),"Parent ready");
            a.platform=new TestPlatform(direct,lan);b.platform=new TestPlatform(direct,lan);a.platform.peer=b.platform;b.platform.peer=a.platform;
            a.files=new AutomaticFileRoutes(a.wire,CoreTests.A,"A",ka,na,CoreTests.B,kb.publicKey(),route,()->a.approved,a.platform,a);b.files=new AutomaticFileRoutes(b.wire,CoreTests.B,"B",wrongRoot?DeviceIdentity.generate():kb,nb,CoreTests.A,ka.publicKey(),route,()->b.approved,b.platform,b);
            a.files.start();b.files.start();
        }
        private StreamConnection path(StreamConnection base,End end){return new StreamConnection(){public InputStream input()throws IOException{return base.input();}public OutputStream output()throws IOException{return base.output();}public String label(){return base.label();}public SocketPath socketPath(){return end.nativePath;}public void close()throws IOException{base.close();}};}
        void transfer()throws Exception {a.files.ensureReady().get(5,TimeUnit.SECONDS);b.files.ensureReady().get(5,TimeUnit.SECONDS);TransferPacket packet=TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),a.wire.connectionGeneration(),4,0,new byte[]{1,2,3,4});check(a.files.send(packet),"Selected lane sends: "+a.files.diagnostics()+" error="+a.errors.peek());TransferPacket got=b.received.poll(5,TimeUnit.SECONDS);check(got!=null&&got.connectionGeneration.equals(a.wire.connectionGeneration()),"File packets retain logical parent generation");}
        public void close(){a.files.close();b.files.close();a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);}
    }
}
