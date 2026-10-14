package dev.ghost.nearbyim;

import android.app.Instrumentation;
import android.net.Uri;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.Peer;
import dev.ghost.nearbyim.transport.AndroidFilePlatform;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real controller/Noise/receipt regression. Loopback here does NOT validate P2P radio establishment. */
final class AndroidWifiDirectSessionChecks {
    private static int checks;
    static int run(Instrumentation instrumentation)throws Exception {
        checks=0;
        directChatRejected(instrumentation);
        pausedLiveRouteRetained(instrumentation);
        rejectedChatSocketRoles(instrumentation);
        for(boolean incoming:new boolean[]{true,false}) {
            boolean requested=!incoming;
            try(Fixture fixture=new Fixture(instrumentation,incoming,requested,false)) {
                fixture.ready(requested);
                fixture.bidirectionalFiles();
                check(onMain(instrumentation,()->fixture.controller.transferDiagnostics().contains("transport=lan\n")),"Controller loses actual LAN chat diagnostic");
                AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"pending.bin","application/octet-stream",32,"0".repeat(64),0);
                check(onMain(instrumentation,()->fixture.controller.attachmentRoute(fixture.peer,new AttachmentRecord(info,true,"transferring",0).withRoute("lan"))==TransferPath.LAN_UNCONFIRMED),"Native approved LAN session displays the persisted file route");
            }
        }
        try(Fixture fixture=new Fixture(instrumentation,false,true,true)) {
            await(()->onMain(instrumentation,()->!fixture.controller.error.isEmpty()),"Selected chat identity mismatch is rejected");
            check(onMain(instrumentation,()->!fixture.controller.connected&&fixture.controller.error.key.equals("identityVerificationFailed")),"Selected chat socket bypasses recipient identity");
            check(onMain(instrumentation,()->fixture.controller.routeNotice.isEmpty()),"Identity failure silently downgrades to another path");
        }
        try(Fixture fixture=new Fixture(instrumentation,false,true,false,Peer.BLUETOOTH,true)) {
            fixture.ready(true);
            FramedSession chat=onMain(instrumentation,()->field(fixture.controller,"active"));
            fixture.bidirectionalFiles();
            check(onMain(instrumentation,()->fixture.controller.connected&&fixture.controller.sessionMode==Peer.BLUETOOTH&&field(fixture.controller,"active")==chat),"Independent file LAN changed Bluetooth chat or its authorization stream");
            check(onMain(instrumentation,()->fixture.controller.messages.stream().filter(message->message.attachment!=null&&Set.of("received","delivered").contains(message.attachment.state)).anyMatch(message->message.attachment.route.equals("lan"))),"Bluetooth controller files did not use the separate native LAN listener");
            String id=UUID.randomUUID().toString();check(fixture.remote.send(new Frame(Frame.TEXT,id,"text during separate file lane",System.currentTimeMillis())),"Original chat stream cannot send text after file routing");
            check(id.equals(fixture.textAcks.poll(10,TimeUnit.SECONDS)),"Original chat receipt missing after file routing");
        }
        return checks;
    }
    private static void directChatRejected(Instrumentation instrumentation)throws Exception {
        ChatController controller=onMain(instrumentation,()->new ChatController(instrumentation.getTargetContext(),()->{}));
        try {onMain(instrumentation,()->{
            controller.directRunning=true;
            Method begin=ChatController.class.getDeclaredMethod("beginConnect",int.class,String.class,boolean.class,String.class);begin.setAccessible(true);
            check(!(boolean)begin.invoke(controller,Peer.WIFI_DIRECT,UUID.randomUUID().toString(),false,null),"Direct is a file channel, never a third chat mode");
            check(!controller.connecting&&!controller.hasSession(),"Rejected file-only mode created a chat attempt");
            return null;
        });}finally{onMain(instrumentation,()->{controller.destroy();return null;});}
    }
    private static void rejectedChatSocketRoles(Instrumentation instrumentation)throws Exception {
        for(boolean incoming:new boolean[]{true,false}){
            ChatController controller=onMain(instrumentation,()->new ChatController(instrumentation.getTargetContext(),()->{}));
            java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
            try{onMain(instrumentation,()->{
                controller.lanRunning=true;
                if(incoming){Method begin=ChatController.class.getDeclaredMethod("beginConnect",int.class,String.class,boolean.class,String.class);begin.setAccessible(true);check((boolean)begin.invoke(controller,Peer.LAN,UUID.randomUUID().toString(),false,null),"Requested chat rejection fixture cannot begin");}
                StreamConnection stream=new StreamConnection(){public InputStream input(){throw new AssertionError("Rejected chat role read a stream");}public OutputStream output(){throw new AssertionError("Rejected chat role wrote a stream");}public String label(){return "Rejected chat role";}public void close(){closed.set(true);}};
                controller.onConnection(Peer.LAN,stream,incoming);
                check(closed.get()&&!controller.hasSession()&&!controller.connected,"An unsolicited outgoing/requested incoming LAN socket was treated as a file-lane role");return null;
            });}finally{onMain(instrumentation,()->{controller.destroy();return null;});}
        }
    }
    private static void pausedLiveRouteRetained(Instrumentation instrumentation)throws Exception {
        ChatController controller=onMain(instrumentation,()->new ChatController(instrumentation.getTargetContext(),()->{}));
        String peer=UUID.randomUUID().toString();
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"paused-route.bin","application/octet-stream",32,"0".repeat(64),0);
        try {
            ChatStore store=field(controller,"store");
            AttachmentRecord durable=new AttachmentRecord(info,true,"paused",16).withRoute("lan");
            store.attachment(peer,"Paused route fixture",durable);
            List<ChatStore.Message> cached=store.messages(peer);
            check(cached.size()==1&&cached.get(0).attachment.route.equals("lan"),"Native persisted paused receipt lost actual LAN route");
            Method overlay=ChatController.class.getDeclaredMethod("liveMessages",List.class,String.class);overlay.setAccessible(true);
            Constructor<?> feedback=Class.forName(ChatController.class.getName()+"$Feedback").getDeclaredConstructor(AttachmentRecord.class,TransferProgress.class);feedback.setAccessible(true);
            for(String state:List.of("paused","transferring")) {
                AttachmentRecord previous=new AttachmentRecord(info,true,state,16).withRoute("lan");
                List<ChatStore.Message> history=state.equals("paused")?cached:List.of(new ChatStore.Message(info.id,info.name,"pending",true,0,previous));
                AttachmentRecord fresh=new AttachmentRecord(info,true,"paused",16);
                onMain(instrumentation,()->{
                    Map<String,Object> measurements=field(controller,"transferProgress");measurements.put(peer+":"+info.id+":true",feedback.newInstance(fresh,TransferProgress.UNKNOWN));
                    @SuppressWarnings("unchecked") List<ChatStore.Message> live=(List<ChatStore.Message>)overlay.invoke(controller,history,peer);
                    check(live.get(0).attachment.state.equals("paused")&&live.get(0).attachment.route.equals("lan"),"Blank post-loss feedback erased actual route for "+state+" cached history");
                    check(controller.attachmentRoute(peer,live.get(0).attachment)==TransferPath.LAN_UNCONFIRMED,"Paused live bubble no longer shows last actual file channel");return null;
                });
            }
            onMain(instrumentation,()->{
                Map<String,Object> measurements=field(controller,"transferProgress");
                measurements.put(peer+":"+info.id+":true",feedback.newInstance(new AttachmentRecord(info,false,"paused",16),TransferProgress.UNKNOWN));
                @SuppressWarnings("unchecked") List<ChatStore.Message> rejected=(List<ChatStore.Message>)overlay.invoke(controller,cached,peer);
                check(rejected.get(0)==cached.get(0),"Opposite-direction feedback replaced cached attachment metadata");
                measurements.put(peer+":"+info.id+":true",feedback.newInstance(new AttachmentRecord(info,true,"paused",16).withRoute("bluetooth"),TransferProgress.UNKNOWN));
                @SuppressWarnings("unchecked") List<ChatStore.Message> changed=(List<ChatStore.Message>)overlay.invoke(controller,cached,peer);
                check(changed.get(0).attachment.route.equals("bluetooth"),"Known newer actual file route was hidden by cached LAN route");return null;
            });
        }finally{onMain(instrumentation,()->{controller.destroy();return null;});}
    }
    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation;
        final ChatController controller;
        final String peer=UUID.randomUUID().toString();
        final DeviceIdentity root=DeviceIdentity.generate();
        final Path directory;
        final FramedSession remote;
        final AtomicReference<AttachmentTransferV2> engine=new AtomicReference<>();
        final AtomicReference<AutomaticFileRoutes> routes=new AtomicReference<>();
        final int chatMode;
        final boolean automatic;
        final boolean incoming,requested;

        final BlockingQueue<AttachmentRecord> records=new LinkedBlockingQueue<>();
        final BlockingQueue<String> textAcks=new LinkedBlockingQueue<>();
        final AtomicReference<UiText> remoteClosed=new AtomicReference<>();
        final AtomicReference<String> remoteFailure=new AtomicReference<>();
        Fixture(Instrumentation instrumentation,boolean incoming,boolean requested,boolean mismatch)throws Exception {
            this(instrumentation,incoming,requested,mismatch,Peer.LAN,false);
        }
        Fixture(Instrumentation instrumentation,boolean incoming,boolean requested,boolean mismatch,int chatMode,boolean automatic)throws Exception {
            this.instrumentation=instrumentation;this.chatMode=chatMode;this.automatic=automatic;this.incoming=incoming;this.requested=requested;
            controller=onMain(instrumentation,()->new ChatController(instrumentation.getTargetContext(),()->{}));
            directory=Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),"direct-noise-");
            onMain(instrumentation,()->{
                // The emulator has no P2P radio. Exercise the actual socket-delivery boundary independently.
                if(chatMode==Peer.LAN)controller.lanRunning=true;else controller.bluetoothRunning=true;
                if(requested){Method begin=ChatController.class.getDeclaredMethod("beginConnect",int.class,String.class,boolean.class,String.class);begin.setAccessible(true);check((boolean)begin.invoke(controller,chatMode,mismatch?UUID.randomUUID().toString():peer,true,null),"Native requested chat session cannot begin");}
                else controller.selectConversation(new ChatStore.Conversation(peer,"Native chat fixture",0));
                return null;
            });
            Socket local,far;
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {far=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());local=server.accept();}
            AtomicReference<FramedSession> session=new AtomicReference<>();byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
            remote=FramedSession.secure(connection(far),peer,"Native chat fixture",root,key,incoming,new FramedSession.Listener(){
                public void onHello(Frame hello){try{
                    FramedSession wire=session.get();
                    engine.set(new AttachmentTransferV2(directory,root.fingerprint(),DeviceIdentity.fingerprint(wire.remotePublicKey()),wire.connectionGeneration(),false,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){AutomaticFileRoutes selected=routes.get();return automatic?selected!=null&&selected.send(packet):wire.sendTransfer(packet);}public void abort(){AutomaticFileRoutes selected=routes.get();if(selected!=null)selected.abort();else wire.close(UiText.of("attachmentFailed"));}},record->records.add(record)));
                    wire.approve();
                }catch(Exception failure){throw new IllegalStateException("Native encrypted chat fixture setup failed",failure);}}
                public void onTransfer(TransferPacket packet){AutomaticFileRoutes selected=routes.get();if(selected!=null)selected.receiveChatTransfer(packet);else try{engine.get().receive(packet,true);}catch(IOException failure){throw new UncheckedIOException(failure);}}
                public void onFileRoute(FileRoutePacket packet){AutomaticFileRoutes selected=routes.get();if(selected!=null)selected.receiveControl(packet);}
                public void onText(Frame text){session.get().acknowledge(text.id);}
                public void onAck(String id){textAcks.add(id);}
                public void onReady(){if(automatic){
                    FramedSession wire=session.get();
                    AutomaticFileRoutes selected=new AutomaticFileRoutes(wire,peer,"Native FILE fixture",root,key,controller.localId,wire.remotePublicKey(),chatMode==Peer.LAN?"lan":"bluetooth",()->wire.isReady(),lanOnlyPlatform(instrumentation,peer,controller.localId),new AutomaticFileRoutes.Listener(){
                        public void receive(TransferPacket packet){try{engine.get().receive(packet,true);}catch(IOException failure){throw new UncheckedIOException(failure);}}
                        public void changed(){}
                        public void failed(IOException failure){remoteClosed.compareAndSet(null,UiText.of("attachmentFailed"));}
                    });routes.set(selected);selected.start();
                }}
                public void onClosed(UiText reason){remoteClosed.compareAndSet(null,reason);}
                public void onFailure(IOException failure){remoteFailure.compareAndSet(null,boundedFailure(failure));}
            },false,automatic); // This file/consent peer has no AvatarSync; do not advertise an unimplemented handler.
            session.set(remote);remote.start();
            onMain(instrumentation,()->{controller.onConnection(chatMode,connection(local),incoming);return null;});
        }
        void ready(boolean requested)throws Exception {
            if(!requested){awaitState(()->onMain(instrumentation,()->controller.approvalId!=null),"Incoming LAN chat still requires consent");check(!remote.isReady(),"Incoming chat automatically approves unknown contact");onMain(instrumentation,()->{controller.approve(controller.approvalId,false);return null;});}
            awaitState(()->onMain(instrumentation,()->controller.connected&&controller.canSendAttachment()),"Both supported chat socket roles create ready production file engines");
            check(remote.attachmentsV2()&&!remote.avatarsSupported(),"Chat fixture must negotiate its implemented encrypted capabilities");
            check(onMain(instrumentation,()->controller.approvalId==null&&peer.equals(controller.selectedId)&&controller.endToEndEncrypted(peer)),"Chat socket direction changes selected peer or encryption");
            if(requested)await(()->onMain(instrumentation,()->controller.trustedDevices.stream().anyMatch(device->device.id.equals(peer)&&device.mode==chatMode&&device.publicKey.equals(root.publicKey())&&device.bluetoothAddress==null)),"Remembered chat session persists its root and mode without a storage disconnect");
            String id=UUID.randomUUID().toString();check(remote.send(new Frame(Frame.TEXT,id,"native consent proof",System.currentTimeMillis())),"Native chat fixture sends encrypted text");
            String ack=textAcks.poll(10,TimeUnit.SECONDS);
            check(id.equals(ack),"Native saved-text receipt missing: ack="+ack+", remoteClosed="+remoteClosed.get()+", controller="+onMain(instrumentation,()->controller.status.key+"/"+controller.error.key));
            await(()->onMain(instrumentation,()->controller.messages.stream().anyMatch(message->message.id.equals(id)&&!message.outgoing&&message.text.equals("native consent proof"))),"Acknowledged native chat text is present in saved history");
        }
        private void awaitState(Callable<Boolean> condition,String message)throws Exception {
            try{await(condition,message);}catch(AssertionError timeout){throw new AssertionError(message+"; "+stateSnapshot(),timeout);}
        }
        private String stateSnapshot()throws Exception {
            String nativeState=onMain(instrumentation,()->{
                String setup;
                try{
                    FramedSession wire=field(controller,"active");CompletableFuture<?> preparing=field(controller,"attachmentsPreparing");
                    setup=",wireReady="+(wire!=null&&wire.isReady())+",transfers="+(field(controller,"transfers")!=null)+",preparing="+(preparing!=null)+",preparedDone="+(preparing!=null&&preparing.isDone())+",preparedFailed="+(preparing!=null&&preparing.isCompletedExceptionally());
                }catch(Exception unavailable){setup=",privateSnapshot="+unavailable.getClass().getSimpleName();}
                return "incoming="+incoming+",requested="+requested+",mode="+chatMode+",automatic="+automatic+
                    ",connected="+controller.connected+",connecting="+controller.connecting+",status="+controller.status.key+",error="+controller.error.key+
                    ",active="+controller.hasSession()+",consent="+(controller.approvalId!=null)+",peerMatch="+peer.equals(controller.connectedPeerId)+",selectedMatch="+peer.equals(controller.selectedId)+
                    ",canSendAttachment="+controller.canSendAttachment()+setup;
            });
            UiText reason=remoteClosed.get();return nativeState+",remoteReady="+remote.isReady()+",remoteEngine="+(engine.get()!=null)+",remoteClosed="+(reason==null?"none":reason.key)+",remoteFailure="+remoteFailure.get();
        }
        void bidirectionalFiles()throws Exception {
            byte[] expected=new byte[65539];new Random(927).nextBytes(expected);
            Path source=directory.resolve("source.bin");Files.write(source,expected);
            if(automatic){await(()->routes.get()!=null,"Remote automatic FILE coordinator missing");routes.get().ensureReady().get(80,TimeUnit.SECONDS);}
            String id=engine.get().offer(new FileAttachmentSource(source),"direct-roundtrip.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            await(()->onMain(instrumentation,()->controller.messages.stream().anyMatch(message->message.id.equals(id)&&message.attachment!=null&&message.attachment.state.equals("received"))),"Native file receiver saves file");
            AttachmentRecord received=onMain(instrumentation,()->controller.messages.stream().filter(message->message.id.equals(id)).findFirst().orElseThrow().attachment);
            Path receivedFile=controller.attachmentPath(peer,received.info).get(10,TimeUnit.SECONDS);
            check(Arrays.equals(expected,Files.readAllBytes(receivedFile)),"Native file receiver changes original bytes");
            Uri uri=new Uri.Builder().scheme("content").authority(instrumentation.getTargetContext().getPackageName()+".attachments").appendPath(peer).appendPath(receivedFile.getFileName().toString()).appendQueryParameter("name",received.info.name).appendQueryParameter("mime",received.info.mime).build();
            onMain(instrumentation,()->{controller.sendAttachment(peer,controller.attachmentSessionToken(),uri);return null;});
            AttachmentRecord returned=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline){AttachmentRecord next=records.poll(1,TimeUnit.SECONDS);if(next!=null&&!next.outgoing&&next.state.equals("received")){returned=next;break;}}
            check(returned!=null,"Native file sender never completes durable return file");
            check(Arrays.equals(expected,Files.readAllBytes(AttachmentTransfer.file(directory,returned.info,false))),"Return file bytes differ after encrypted chunks");
            String returnedId=returned.info.id;
            await(()->onMain(instrumentation,()->controller.messages.stream().anyMatch(message->message.id.equals(returnedId)&&message.attachment!=null&&message.attachment.state.equals("delivered"))),"Native file sender receives durable saved receipt");
        }
        public void close()throws Exception {
            if(routes.get()!=null)routes.get().close();
            remote.close(UiText.of("connectionEnded"));
            if(engine.get()!=null)engine.get().shutdown().get(10,TimeUnit.SECONDS);
            onMain(instrumentation,()->{controller.destroy();return null;});
        }
    }
    private static AutomaticFileRoutes.Platform lanOnlyPlatform(Instrumentation instrumentation,String localId,String remoteId){
        AndroidFilePlatform nativeLan=new AndroidFilePlatform(instrumentation.getTargetContext(),localId,"Native FILE fixture",remoteId);
        return new AutomaticFileRoutes.Platform(){
            public boolean directSupported(){return false;}
            public void prepareLan(java.util.function.Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints,java.util.function.Consumer<StreamConnection> accepted){nativeLan.prepareLan(endpoints,accepted);}
            public void connectLan(List<AutomaticFileRoutes.Endpoint> endpoints,java.util.function.Consumer<StreamConnection> opened,java.util.function.Consumer<Throwable> failed){nativeLan.connectLan(endpoints,opened,failed);}
            public void openDirect(boolean dialer,java.util.function.Consumer<AutomaticFileRoutes.Opened> opened,java.util.function.Consumer<Throwable> failed){failed.accept(new IOException("Native LAN fixture excludes a P2P radio claim"));}
            public void stopDirect(){nativeLan.stopDirect();}
            public void close(){nativeLan.close();}
        };
    }
    private static String boundedFailure(IOException failure){String text=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage()).replace('\n',' ').replace('\r',' ');return text.substring(0,Math.min(320,text.length()));}
    @SuppressWarnings("unchecked") private static <T>T field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);}
    private static StreamConnection connection(Socket socket){return new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "controller fixture, no P2P radio";}public void close()throws IOException{socket.close();}};}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception{Object[] result=new Object[2];instrumentation.runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private static void await(Callable<Boolean> condition,String message)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(20);}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
