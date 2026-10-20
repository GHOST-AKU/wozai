package dev.ghost.wozai;
import dev.ghost.nearbyim.core.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.net.*;
import dev.ghost.nearbyim.i18n.UiText;
public final class AttachmentStoreTests {
    static final String PEER="12345678-1234-1234-1234-123456789abc",ID="12345678-1234-1234-1234-123456789abd";
    static volatile Runnable timeoutEvidence=()->{};
    static final class Events implements DesktopClient.Listener {
        final AtomicReference<DesktopClient.State> state=new AtomicReference<>();
        public void changed(DesktopClient.State value){state.set(value);}
        public void request(DesktopClient.Request request){throw new AssertionError("Pinned peer asked for consent");}
        volatile String noticeKey="";public void notice(UiText text){noticeKey=text.key;}
        boolean ready(){return state.get()!=null&&state.get().phase().equals("ready");}
    }
    static void await(java.util.concurrent.Callable<Boolean> condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(10);}timeoutEvidence.run();throw new AssertionError("Desktop attachment timed out");}
    static AttachmentRecord record(DesktopStore store,String peer,String id)throws Exception{return store.messages(peer).stream().filter(m->m.id().equals(id)).findFirst().map(DesktopStore.Message::attachment).orElse(null);}
    static String generation(DesktopClient client)throws Exception {
        Field current=DesktopClient.class.getDeclaredField("current");current.setAccessible(true);Object session=current.get(client);
        Field wire=session.getClass().getDeclaredField("wire");wire.setAccessible(true);return ((FramedSession)wire.get(session)).connectionGeneration();
    }
    /** Hold a real file read at 8 MiB so pause cannot race with complete delivery.
     * The production source reference stays identical; reconnect uses the real resolver. */
    static final class PauseSource implements AttachmentSource {
        final FileAttachmentSource file;
        final CountDownLatch reached=new CountDownLatch(1),released=new CountDownLatch(1);
        PauseSource(Path path)throws java.io.IOException {file=new FileAttachmentSource(path);}
        public long size(){return file.size();}public String generation(){return file.generation();}
        public boolean seekable(){return true;}public String persistentReference(){return file.persistentReference();}
        public void verifyUnchanged()throws java.io.IOException {file.verifyUnchanged();}
        public java.io.InputStream open(long offset)throws java.io.IOException {
            return new java.io.FilterInputStream(file.open(offset)){
                long position=offset;
                public int read(byte[] bytes,int begin,int count)throws java.io.IOException {
                    if(position>=8L*1024*1024){reached.countDown();try{if(!released.await(15,TimeUnit.SECONDS))throw new java.io.IOException("Pause fixture barrier timed out");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.InterruptedIOException();}}
                    int n=super.read(bytes,begin,count);if(n>0)position+=n;return n;
                }
                public void close()throws java.io.IOException {released.countDown();super.close();}
            };
        }
        public void close()throws java.io.IOException {released.countDown();file.close();}
    }
    static String offerPaused(DesktopClient client,PauseSource source)throws Exception {
        Field current=DesktopClient.class.getDeclaredField("current");current.setAccessible(true);Object session=current.get(client);
        Field routes=session.getClass().getDeclaredField("files");routes.setAccessible(true);
        ((AutomaticFileRoutes)routes.get(session)).ensureReady().get(10,TimeUnit.SECONDS);
        Field files=session.getClass().getDeclaredField("transfers");files.setAccessible(true);
        return ((ClientAttachmentTransfers)files.get(session)).offer(source,"resume.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
    }
    static void clientTransfer(Path root)throws Exception {
        Path a=root.resolve("a"),b=root.resolve("b");Files.createDirectories(a);Files.createDirectories(b);var ai=DesktopIdentity.load(a.resolve("identity.properties"));var bi=DesktopIdentity.load(b.resolve("identity.properties"));Events ae=new Events(),be=new Events();
        InetAddress address=Collections.list(NetworkInterface.getNetworkInterfaces()).stream().flatMap(i->Collections.list(i.getInetAddresses()).stream()).filter(i->i instanceof Inet4Address&&LocalEndpoint.isLocal(i)).findFirst().orElseThrow();
        Path retainedPhoto=null;AttachmentInfo sentPhoto=null;
        try(DesktopStore as=new DesktopStore(a);DesktopStore bs=new DesktopStore(b);DesktopClient ac=new DesktopClient(as,ai,ae);DesktopClient bc=new DesktopClient(bs,bi,be)){
            timeoutEvidence=()->{
                System.err.println("A notice="+ae.noticeKey+"\n"+ac.transferDiagnostics(bi.id()));
                System.err.println("B notice="+be.noticeKey+"\n"+bc.transferDiagnostics(ai.id()));
                try{for(DesktopStore store:new DesktopStore[]{as,bs})for(DesktopStore.Message message:store.messages(store==as?bi.id():ai.id()))if(message.attachment()!=null){AttachmentRecord r=message.attachment();System.err.println((store==as?"A":"B")+" attachment outgoing="+r.outgoing+", state="+r.state+", transferred="+r.transferred+", total="+r.info.size);}}catch(Exception ignored){}
            };
            int port=bc.listen().get(10,TimeUnit.SECONDS).port();String endpoint=DesktopClient.endpoint(address,port);as.peer(new DesktopStore.Peer(bi.id(),"b",bi.signer().publicKey(),endpoint));bs.peer(new DesktopStore.Peer(ai.id(),"a",ai.signer().publicKey(),""));ac.connect(endpoint,bi.id()).get(10,TimeUnit.SECONDS);await(()->ae.ready()&&be.ready());
            if(!ac.endToEndEncrypted(bi.id())||!bc.endToEndEncrypted(ai.id()))throw new AssertionError("Production file session does not report verified end-to-end encryption");
            byte[] data=new byte[32768*8+7];new Random(91).nextBytes(data);Path source=root.resolve("document.pdf");Files.write(source,data);
            String id=ac.sendAttachment(bi.id(),source).get(10,TimeUnit.SECONDS);await(()->record(as,bi.id(),id)!=null&&record(bs,ai.id(),id)!=null&&record(as,bi.id(),id).state.equals("delivered")&&record(bs,ai.id(),id).state.equals("received"));AttachmentRecord offered=record(bs,ai.id(),id);
            Path received=bc.attachmentPath(ai.id(),offered.info).get(10,TimeUnit.SECONDS);if(!Arrays.equals(data,Files.readAllBytes(received))||!received.toString().endsWith(".pdf"))throw new AssertionError("Desktop attachment bytes or extension changed");
            Path photo=root.resolve("photo.png");Files.write(photo,data);String photoId=ac.sendAttachment(bi.id(),photo).get(10,TimeUnit.SECONDS);
            await(()->record(as,bi.id(),photoId)!=null&&record(as,bi.id(),photoId).state.equals("delivered"));AttachmentRecord sent=record(as,bi.id(),photoId);
            Path retained=ac.attachmentPath(bi.id(),sent).get(10,TimeUnit.SECONDS);if(!Arrays.equals(data,Files.readAllBytes(retained)))throw new AssertionError("Sent photo was not retained");
            retainedPhoto=retained;sentPhoto=sent.info;
            // Exercise the real factory, storage and reconnect path, including a fresh Noise transcript.
            Path large=root.resolve("resume.bin");byte[] block=new byte[1024*1024];new Random(123).nextBytes(block);
            try(java.io.OutputStream out=Files.newOutputStream(large)){for(int n=0;n<64;n++)out.write(block);}
            PauseSource pauseSource=new PauseSource(large);
            String oldGeneration=generation(ac),resumeId=offerPaused(ac,pauseSource);
            if(!pauseSource.reached.await(10,TimeUnit.SECONDS))throw new AssertionError("Pause fixture never reached its real file barrier");
            ac.attachmentAction(bi.id(),resumeId,true,"pause").get(10,TimeUnit.SECONDS);
            await(()->record(as,bi.id(),resumeId).state.equals("paused")&&record(bs,ai.id(),resumeId).state.equals("paused"));
            ac.disconnect().get(10,TimeUnit.SECONDS);await(()->!ae.ready()&&!be.ready());
            Field barrier=DesktopClient.class.getDeclaredField("attachmentsStopped");barrier.setAccessible(true);barrier.set(ac,CompletableFuture.failedFuture(new java.io.IOException("Previously reported pause persistence failure; writer already stopped")));
            ac.connect(endpoint,bi.id()).get(10,TimeUnit.SECONDS);await(()->ae.ready()&&be.ready());
            if(oldGeneration.equals(generation(ac)))throw new AssertionError("Reconnect reused a Noise generation");
            ac.attachmentAction(bi.id(),resumeId,true,"resume").get(10,TimeUnit.SECONDS);
            await(()->record(as,bi.id(),resumeId).state.equals("delivered")&&record(bs,ai.id(),resumeId).state.equals("received"));
            Path resumed=bc.attachmentPath(ai.id(),record(bs,ai.id(),resumeId)).get(10,TimeUnit.SECONDS);
            if(Files.mismatch(large,resumed)!=-1)throw new AssertionError("Authenticated reconnect changed resumed content");
            try(java.io.OutputStream out=Files.newOutputStream(large,StandardOpenOption.APPEND)){for(int n=64;n<512;n++)out.write(block);}
            for(boolean senderInitiates:new boolean[]{true,false}) {
                String sameGeneration=generation(ac),sameId=ac.sendAttachment(bi.id(),large).get(10,TimeUnit.SECONDS);
                await(()->record(bs,ai.id(),sameId)!=null&&record(bs,ai.id(),sameId).transferred>=32L*1024*1024);
                DesktopClient initiator=senderInitiates?ac:bc;String remote=senderInitiates?bi.id():ai.id();
                initiator.attachmentAction(remote,sameId,senderInitiates,"pause").get(10,TimeUnit.SECONDS);
                await(()->record(as,bi.id(),sameId).state.equals("paused")&&record(bs,ai.id(),sameId).state.equals("paused"));
                long resumeBegin=System.nanoTime();initiator.attachmentAction(remote,sameId,senderInitiates,"resume").get(10,TimeUnit.SECONDS);
                await(()->record(as,bi.id(),sameId).state.equals("delivered")&&record(bs,ai.id(),sameId).state.equals("received"));
                if(!sameGeneration.equals(generation(ac))||Files.mismatch(large,bc.attachmentPath(ai.id(),record(bs,ai.id(),sameId)).get())!=-1)throw new AssertionError("Same-session resume changed generation or file bytes");
                System.out.println("Production Noise same-session resume: 512 MiB, "+(senderInitiates?"sender":"receiver")+" initiated, complete in "+((System.nanoTime()-resumeBegin)/1e9)+"s (virtual LAN, not Android/Windows acceptance)");
            }
            try(var file=java.nio.channels.FileChannel.open(large,StandardOpenOption.WRITE)){file.truncate(256L*1024*1024);}
            String ab=ac.sendAttachment(bi.id(),large).get(10,TimeUnit.SECONDS),ba=bc.sendAttachment(ai.id(),large).get(10,TimeUnit.SECONDS);
            long[] ackNs=new long[20];int overlapping=0;
            for(int n=0;n<ackNs.length;n++) {
                DesktopClient sender=n%2==0?ac:bc;DesktopStore senderStore=n%2==0?as:bs;String peer=n%2==0?bi.id():ai.id();String text="latency-"+UUID.randomUUID();
                if(record(as,bi.id(),ab).active()||record(bs,ai.id(),ba).active())overlapping++;
                long begin=System.nanoTime();
                try{if(!sender.send(text).get(1,TimeUnit.SECONDS))throw new AssertionError("File traffic rejected a text probe");}
                catch(TimeoutException timeout){timeoutEvidence.run();Thread.getAllStackTraces().forEach((thread,stack)->{if(thread.getName().contains("wozai")||thread.getName().contains("attachment")){System.err.println("Timeout thread "+thread.getName()+" state="+thread.getState());for(StackTraceElement frame:stack)System.err.println("  "+frame);}});throw timeout;}
                await(()->senderStore.messages(peer).stream().anyMatch(m->m.body().equals(text)&&m.status().equals("delivered")));ackNs[n]=System.nanoTime()-begin;
            }
            Arrays.sort(ackNs);double p95=ackNs[18]/1e6;
            checkTextAckPerformance(overlapping,p95);
            await(()->record(as,bi.id(),ab).state.equals("delivered")&&record(bs,ai.id(),ba).state.equals("delivered"));
            if(Files.mismatch(large,bc.attachmentPath(ai.id(),record(bs,ai.id(),ab)).get())!=-1||Files.mismatch(large,ac.attachmentPath(bi.id(),record(as,bi.id(),ba)).get())!=-1)throw new AssertionError("Duplex file content changed");
            String canceled=ac.sendAttachment(bi.id(),large).get(10,TimeUnit.SECONDS);
            // Measure cancellation of a published offer; before publication no remote row exists.
            await(()->record(bs,ai.id(),canceled)!=null);long cancelBegin=System.nanoTime();
            ac.attachmentAction(bi.id(),canceled,true,"cancel").get(1,TimeUnit.SECONDS);
            double cancelMs=(System.nanoTime()-cancelBegin)/1e6;
            await(()->record(as,bi.id(),canceled).state.equals("canceled")&&record(bs,ai.id(),canceled).state.equals("canceled"));
            if(cancelMs>1000||!Files.isRegularFile(large))throw new AssertionError("Cancellation latency or original-file ownership failed");
            Field feedback=DesktopClient.class.getDeclaredField("transferProgress");feedback.setAccessible(true);
            await(()->((Map<?,?>)feedback.get(ac)).isEmpty()&&((Map<?,?>)feedback.get(bc)).isEmpty());
            System.out.println("Real desktop duplex: 256 MiB each direction, "+overlapping+" overlapping saved text ACKs; P95="+p95+"ms; cancel="+cancelMs+"ms (virtual LAN, not phone/RFCOMM acceptance)");
            Path exported=root.resolve("exported.pdf");Files.copy(received,exported);bc.clear(ai.id()).get(10,TimeUnit.SECONDS);if(Files.exists(received)||!Files.exists(exported)||bs.peer(ai.id()).publicKey().isEmpty())throw new AssertionError("Clear damaged independent export or trust");
            boolean refused=false;try{ac.sendAttachment(UUID.randomUUID().toString(),source).get(10,TimeUnit.SECONDS);}catch(ExecutionException expected){refused=true;}if(!refused)throw new AssertionError("Wrong peer selector sent a file");
        }
        timeoutEvidence=()->{};
        try(DesktopStore restarted=new DesktopStore(a)){if(!Files.exists(restarted.attachmentFile(bi.id(),sentPhoto,true)))throw new AssertionError("Restart removed delivered photo");restarted.clear(bi.id());if(Files.exists(retainedPhoto))throw new AssertionError("Clear retained sent photo bytes");}
    }
    static void checkTextAckPerformance(int overlapping,double p95)throws Exception {
        if(overlapping<16)throw new AssertionError("File/text overlap failed: "+overlapping+" probes");
        if(p95>500){
            String failure="LAN text save ACK performance gate failed: P95="+p95+"ms > 500ms";
            if(!Boolean.getBoolean("wozai.test.previewRelease"))throw new AssertionError(failure);
            System.err.println("::warning::PREVIEW_QUALITY_GATE_FAILED: "+failure);
            String report=System.getProperty("wozai.test.acceptanceReport");
            if(report!=null)Files.writeString(Path.of(report),failure+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        }
    }
    private static void routeHistory()throws Exception {
        Path root=Files.createTempDirectory("route-history-");
        try {
            AttachmentInfo info=AttachmentInfo.v2(ID,"route.bin","application/octet-stream",8,"0".repeat(64),1);
            try(DesktopStore store=new DesktopStore(root)) {
                store.peer(new DesktopStore.Peer(PEER,"peer","",""));
                store.attachment(PEER,new AttachmentRecord(info,true,"transferring",4).withRoute("wifi-direct"));
                store.attachment(PEER,new AttachmentRecord(info,true,"paused",4));
                if(!record(store,PEER,ID).route.equals("wifi-direct"))throw new AssertionError("Blank paused callback erased actual route");
                store.attachment(PEER,new AttachmentRecord(info,true,"delivered",8));
                store.attachment(PEER,new AttachmentRecord(info,true,"delivered",8));
                if(!record(store,PEER,ID).route.equals("wifi-direct"))throw new AssertionError("Completed history lost actual route");
                terminalRouteReplay(store);
                AttachmentInfo conflict=AttachmentInfo.v2(ID,"other.bin","application/octet-stream",8,"0".repeat(64),1);
                try{store.attachment(PEER,new AttachmentRecord(conflict,true,"delivered",8));throw new AssertionError("Route retention allowed metadata conflict");}catch(java.io.IOException expected){}
            }
            try(DesktopStore store=new DesktopStore(root)){if(!record(store,PEER,ID).route.equals("wifi-direct"))throw new AssertionError("Route lost after store reopen");}
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void terminalRouteReplay(DesktopStore store)throws Exception {
        AttachmentInfo saved=AttachmentInfo.v2(UUID.randomUUID().toString(),"received.bin","application/octet-stream",8,"0".repeat(64),1);
        store.attachment(PEER,new AttachmentRecord(saved,false,"received",8).withRoute("lan"));
        try{store.attachment(PEER,new AttachmentRecord(saved,false,"received",8).withRoute("bluetooth"));}
        catch(java.io.IOException conflict){throw new AssertionError("Identical terminal receipt replay rejects a changed control route",conflict);}
        if(!record(store,PEER,saved.id).route.equals("lan"))throw new AssertionError("Receipt replay overwrote the original file route");
        for(AttachmentInfo changed:new AttachmentInfo[]{
                AttachmentInfo.v2(saved.id,"renamed.bin",saved.mime,8,saved.hash,1),
                AttachmentInfo.v2(saved.id,saved.name,saved.mime,8,"1".repeat(64),1),
                AttachmentInfo.v2(saved.id,saved.name,saved.mime,9,saved.hash,1),
                AttachmentInfo.v2(saved.id,saved.name,saved.mime,8,saved.hash,2)}) {
            try{store.attachment(PEER,new AttachmentRecord(changed,false,"received",changed.size).withRoute("bluetooth"));throw new AssertionError("Terminal route replay allowed conflicting metadata");}catch(java.io.IOException expected){}
        }
        try{store.attachment(PEER,new AttachmentRecord(saved,false,"delivered",8).withRoute("bluetooth"));throw new AssertionError("Terminal route replay allowed a changed final state");}catch(java.io.IOException expected){}
        AttachmentInfo legacy=new AttachmentInfo(UUID.randomUUID().toString(),"legacy.bin","application/octet-stream",8,"0".repeat(64),1);
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();Protocol.write(bytes,legacy.offer());java.io.DataOutputStream out=new java.io.DataOutputStream(bytes);out.writeBoolean(false);out.writeUTF("received");out.writeLong(8);
        AttachmentRecord unknown=AttachmentRecord.decode(Base64.getEncoder().encodeToString(bytes.toByteArray()));
        store.attachment(PEER,unknown);store.attachment(PEER,unknown.withRoute("bluetooth"));
        if(!record(store,PEER,legacy.id).route.isEmpty())throw new AssertionError("Receipt replay invented a route for legacy terminal history");
        try{store.attachment(PEER,new AttachmentRecord(legacy,false,"received",7).withRoute("bluetooth"));throw new AssertionError("Terminal route replay allowed a changed saved length");}catch(java.io.IOException expected){}
    }
    public static void main(String[] args)throws Exception {
        routeHistory();if(args.length>0&&args[0].equals("--routes-only")){System.out.println("AttachmentStoreTests: durable routes preserved across blank callbacks, terminal history, reopen and conflict rejection");return;}
        Path root=Files.createTempDirectory("attachment-store-");
        AttachmentInfo info=new AttachmentInfo(ID,"photo.jpg","image/jpeg",10,"0".repeat(64),1);
        try(DesktopStore store=new DesktopStore(root)) {
            store.peer(new DesktopStore.Peer(PEER,"peer","",""));
            Method save;
            try{save=DesktopStore.class.getMethod("attachment",String.class,AttachmentRecord.class);}
            catch(NoSuchMethodException e){throw new AssertionError("Desktop attachment persistence is missing");}
            save.invoke(store,PEER,new AttachmentRecord(info,true,"awaitingReceipt",10));
        }
        try(DesktopStore store=new DesktopStore(root)) {
            Object record=DesktopStore.Message.class.getMethod("attachment").invoke(store.messages(PEER).get(0));
            if(!((AttachmentRecord)record).state.equals("unknown"))throw new AssertionError("Unacknowledged send falsely completed after restart");
            store.clear(PEER);if(!store.messages(PEER).isEmpty())throw new AssertionError("History retained");
            AttachmentInfo pending=AttachmentInfo.v2(ID,"large.bin","application/octet-stream",10L*1024*1024*1024,null,1);
            store.attachment(PEER,new AttachmentRecord(pending,true,"transferring",4L*1024*1024*1024));
        }
        try(DesktopStore store=new DesktopStore(root)) {
            AttachmentRecord pending=store.messages(PEER).get(0).attachment();if(!pending.state.equals("paused")||pending.transferred!=4L*1024*1024*1024)throw new AssertionError("V2 pending recovery changed");
            store.attachment(PEER,new AttachmentRecord(pending.info,true,"checking",0));store.attachment(PEER,new AttachmentRecord(pending.info,true,"canceled",0));
            try{store.attachment(PEER,new AttachmentRecord(pending.info,true,"checking",0));throw new AssertionError("Canceled attachment resumed");}catch(java.io.IOException expected){}
            store.clear(PEER);
        }
        clientTransfer(root.resolve("pair"));
        System.out.println("AttachmentStoreTests passed");
    }
}
