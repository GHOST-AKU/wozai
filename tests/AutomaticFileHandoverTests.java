package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.ghost.nearbyim.i18n.UiText;

/** Real Noise chat and FILE sockets with deterministic mid-file P2P loss. */
public final class AutomaticFileHandoverTests {
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--binding")){bindingScope();return;}
        if(args.length==1&&args[0].equals("--parent")){for(int repeat=0;repeat<10;repeat++)for(String parent:new String[]{"lan","bluetooth"})try(Harness h=new Harness(parent)){h.dropParent=true;h.transfer();}return;}
        bindingScope();
        try{AutomaticFileRoutes.class.getMethod("attachFiles",ClientAttachmentTransfers.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("FILE routing cannot coordinate checkpoint recovery with its transfer engine",missing);}
        for(String parent:new String[]{"lan","bluetooth"})try(Harness h=new Harness(parent)){
            h.transfer();
            check(h.routes.a.files.route().equals("lan")&&h.routes.b.files.route().equals("lan"),"P2P loss did not automatically choose a working LAN");
            check(h.routes.a.wire.isReady()&&h.routes.b.wire.isReady(),"File recovery disconnected a healthy parent chat");
            check(h.routes.a.errors.isEmpty()&&h.routes.b.errors.isEmpty(),"Automatic recovery exposed a terminal file failure");
        }
        for(String parent:new String[]{"lan","bluetooth"})try(Harness h=new Harness(parent)){
            h.dropParent=true;h.transfer();check(h.routes.a.files.route().equals("lan")&&h.routes.b.files.route().equals("lan"),"Parent loss did not recover through a new LAN Noise lane");
        }
        for(int sender=0;sender<2;sender++)try(Harness h=new Harness("lan",sender,false)){
            h.transfer();check(h.routes.a.files.route().equals("lan"),"A measured faster LAN was not selected: "+h.routes.a.files.diagnostics()+h.routes.b.files.diagnostics());
            check(h.routes.a.files.diagnostics().contains("file_quality_result=lan_faster"),"LAN selection was not based on completed samples");
        }
        try(Harness h=new Harness("bluetooth")){
            h.routes.a.platform.lan=h.routes.b.platform.lan=false;h.transfer();
            check(h.routes.a.files.route().equals("bluetooth"),"LAN unavailable did not preserve the existing Bluetooth fallback");
        }
        try(Harness h=new Harness("bluetooth",0,false)){
            h.routes.a.platform.lanWriteDelay=h.routes.b.platform.lanWriteDelay=70;h.transfer();
            check(h.routes.a.files.route().equals("wifi-direct"),"A slower LAN displaced a healthy P2P lane");
            check(h.routes.a.files.diagnostics().contains("file_quality_result=keep_direct"),"Comparison did not resume the original P2P task");
        }
        for(boolean parentLoss:new boolean[]{false,true})try(Harness h=new Harness("bluetooth",0,false)){
            h.holdPause.set(true);CompletableFuture<Void> sending=CompletableFuture.runAsync(()->{try{h.transfer();}catch(Exception error){throw new CompletionException(error);}});
            check(h.pauseEntered.await(8,TimeUnit.SECONDS),"Quality quiesce did not reach the checkpoint barrier");
            if(parentLoss)h.routes.a.wire.close(UiText.of("disconnected"));else{
                var field=AutomaticFileRoutes.class.getDeclaredField("handover");field.setAccessible(true);Object handover=field.get(h.routes.a.files);
                var method=handover.getClass().getDeclaredMethod("candidateFailed",IOException.class);method.setAccessible(true);
                var actor=AutomaticFileRoutes.class.getDeclaredField("actor");actor.setAccessible(true);
                ((ScheduledThreadPoolExecutor)actor.get(h.routes.a.files)).submit(()->{try{method.invoke(handover,new IOException("Test standby disappeared during checkpoint"));}catch(Exception error){throw new CompletionException(error);}}).get(2,TimeUnit.SECONDS);
            }
            h.releasePause.countDown();sending.get(35,TimeUnit.SECONDS);
            check(h.routes.a.files.route().equals(parentLoss?"lan":"wifi-direct"),"In-flight checkpoint cancellation stranded the original task");
        }
        System.out.println("Automatic FILE handover: LAN/Bluetooth parent, mid-DATA P2P loss, authenticated LAN, original bytes and one saved receipt passed");
    }
    static void bindingScope()throws Exception {
        try(Harness h=new Harness("lan")){
            h.transfer();var field=AutomaticFileRoutes.class.getDeclaredField("handover");field.setAccessible(true);Object recovery=field.get(h.routes.a.files);
            var binding=recovery.getClass().getDeclaredField("binding");binding.setAccessible(true);check(binding.get(recovery)!=null,"Successful recovery did not establish its scoped metadata binding");
            h.routes.a.files.abort();var actor=AutomaticFileRoutes.class.getDeclaredField("actor");actor.setAccessible(true);((ScheduledThreadPoolExecutor)actor.get(h.routes.a.files)).submit(()->{}).get(3,TimeUnit.SECONDS);
            check(binding.get(recovery)==null,"Explicit route termination retained an old recovery-offer authorization");
        }
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    static final class Harness implements AutoCloseable {
        final AutomaticFileRoutesTests.Pair routes;
        final Path root=Files.createTempDirectory("file-handover-"),source=root.resolve("source.bin");
        final byte[] bytes;final int sender;final boolean interrupt,hotspot;boolean dropParent;final CountDownLatch sourceReached=new CountDownLatch(1),sourceRelease=new CountDownLatch(1);
        final ClientAttachmentTransfers[] files=new ClientAttachmentTransfers[2];
        final AtomicLong sent=new AtomicLong();final AtomicBoolean dropped=new AtomicBoolean();
        final CompletableFuture<AttachmentRecord> saved=new CompletableFuture<>(),delivered=new CompletableFuture<>();
        final AtomicBoolean holdPause=new AtomicBoolean();final CountDownLatch pauseEntered=new CountDownLatch(1),releasePause=new CountDownLatch(1);
        final AtomicInteger savedCount=new AtomicInteger();
        Harness(String parent)throws Exception {this(parent,0,true);}
        Harness(String parent,int sender,boolean interrupt)throws Exception {this(parent,sender,interrupt,false);}
        Harness(String parent,int sender,boolean interrupt,boolean hotspot)throws Exception {
            this.hotspot=hotspot;this.sender=sender;this.interrupt=interrupt;bytes=new byte[(interrupt?4:8)*TransferLimits.BLOCK_BYTES+17];
            routes=hotspot?new AutomaticFileRoutesTests.Pair(parent,true,true,false,true,new SocketPath("192.168.43.2",32123,"192.168.43.1",45678,true),new SocketPath("192.168.43.1",45678,"192.168.43.2",32123,false)):new AutomaticFileRoutesTests.Pair(parent,true,true);routes.a.platform.directSuccess=routes.b.platform.directSuccess=true;
            if(!interrupt){routes.a.platform.directWriteDelay=routes.b.platform.directWriteDelay=60;routes.b.prepareDelay=80;}
            new Random(29).nextBytes(bytes);Files.write(source,bytes);
            AutomaticFileRoutesTests.End[] ends={routes.a,routes.b};
            for(int i=0;i<2;i++){
                final int local=i;var end=ends[i];Path directory=root.resolve("p"+i);
                AttachmentTransferV2 engine=new AttachmentTransferV2(directory,DeviceIdentity.fingerprint(ends[1-i].wire.remotePublicKey()),DeviceIdentity.fingerprint(end.wire.remotePublicKey()),end.wire.connectionGeneration(),parent.equals("bluetooth"),new AttachmentTransferV2.Wire(){
                    public boolean send(TransferPacket packet){boolean accepted=end.files.send(packet);
                        if(interrupt&&local==sender&&accepted&&packet.kind==TransferPacket.Kind.DATA&&sent.addAndGet(packet.data.length)>=TransferLimits.BLOCK_BYTES+TransferLimits.DATA_BYTES&&dropped.compareAndSet(false,true))try{if(dropParent)end.wire.close(UiText.of("disconnected"));else selected(end.files).close(UiText.EMPTY);}catch(Exception failure){delivered.completeExceptionally(failure);}
                        if(hotspot&&local==sender&&accepted&&packet.kind==TransferPacket.Kind.DATA&&sent.addAndGet(packet.data.length)>=TransferLimits.BLOCK_BYTES&&dropped.compareAndSet(false,true)){sourceReached.countDown();try{sourceRelease.await(8,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
                        return accepted;}
                    public boolean highBandwidth(){return end.files.highBandwidth();}
                    public boolean restoreOffer(TransferPacket packet){try{return (Boolean)AutomaticFileRoutes.class.getMethod("sendRecoveryOffer",TransferPacket.class).invoke(end.files,packet);}catch(Exception failure){delivered.completeExceptionally(failure);return false;}}
                    public boolean recoverableDisconnect(){return true;}
                    public void abort(){abort(new IOException("Unexpected file pipeline abort"));}
                    public void abort(Throwable failure){end.files.abort();saved.completeExceptionally(failure);delivered.completeExceptionally(failure);}
                },record->{
                    record=end.files.decorate(record);
                    if(record.outgoing&&record.state.equals("paused")&&holdPause.compareAndSet(true,false)){pauseEntered.countDown();try{if(!releasePause.await(5,TimeUnit.SECONDS))throw new IOException("Test checkpoint barrier timed out");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}}
                    if(record.state.equals("received")){try(var out=new FileOutputStream(directory.resolve("received.record").toFile())){out.write(record.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getChannel().force(true);}savedCount.incrementAndGet();saved.complete(record);}
                    if(record.state.equals("delivered"))delivered.complete(record);
                });
                files[i]=new ClientAttachmentTransfers(directory,engine,checkpoint->new FileAttachmentSource(Paths.get(java.net.URI.create(checkpoint.sourceReference().split("\n",2)[0])),checkpoint.key().sourceGeneration()));
                end.guardedConsumer=(packet,current)->{try{files[local].receive(packet,current);}catch(IOException error){delivered.completeExceptionally(error);}};
                AutomaticFileRoutes.class.getMethod("attachFiles",ClientAttachmentTransfers.class).invoke(end.files,files[i]);
            }
        }
        void transfer()throws Exception {
            check(routes.a.files.ensureReady().get(5,TimeUnit.SECONDS)==(hotspot?AutomaticFileRoutes.Route.LAN:AutomaticFileRoutes.Route.WIFI_DIRECT),"Fixture did not begin on P2P");routes.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!routes.a.files.diagnostics().contains("file_handover_supported=true\n")&&System.nanoTime()<deadline)Thread.sleep(5);
            check(routes.a.files.diagnostics().contains("file_handover_supported=true\n"),"New endpoints did not negotiate automatic migration");
            if(dropParent){long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!routes.a.files.diagnostics().contains("file_recovery_lan_known=true")&&System.nanoTime()<until)Thread.sleep(5);check(routes.a.files.diagnostics().contains("file_recovery_lan_known=true"),"Authenticated LAN endpoints were not cached before parent loss");}
            files[sender].enqueue(List.of(AttachmentBatchQueue.selectFile(source))).get(3,TimeUnit.SECONDS);
            AttachmentRecord received,ack;
            try{received=saved.get(45,TimeUnit.SECONDS);ack=delivered.get(45,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(routes.a.files.diagnostics()+"\nPEER\n"+routes.b.files.diagnostics()+"\nERRORS\n"+routes.a.errors+routes.b.errors,error);}
            check(!interrupt||dropped.get(),"Fixture did not interrupt P2P during DATA");
            check(received.info.id.equals(ack.info.id)&&received.info.hash.equals(ack.info.hash),"Recovery changed original file/receipt identity");
            check(Arrays.equals(bytes,Files.readAllBytes(AttachmentTransfer.file(root.resolve("p"+(1-sender)),received.info))),"LAN recovery changed file bytes");
            check(!received.route.isEmpty()&&!ack.route.isEmpty(),"Actual DATA route was not captured");
            check(savedCount.get()==1,"LAN recovery duplicated the durable received event");
        }
        public void close()throws Exception{sourceRelease.countDown();releasePause.countDown();routes.close();for(ClientAttachmentTransfers file:files)if(file!=null)file.shutdown().get(5,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    static FramedSession selected(AutomaticFileRoutes routes)throws Exception{var field=AutomaticFileRoutes.class.getDeclaredField("selected");field.setAccessible(true);return (FramedSession)field.get(routes);}
}
