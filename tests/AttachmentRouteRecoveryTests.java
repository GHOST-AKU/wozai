package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Automatic route recovery preserves user intent and original durable file identities. */
public final class AttachmentRouteRecoveryTests {
    public static void main(String[] args)throws Exception {
        try{ClientAttachmentTransfers.class.getMethod("suspendForRouteChange");}
        catch(NoSuchMethodException missing){throw new AssertionError("Automatic handover has no selective recovery set",missing);}
        try(Pair p=new Pair()){
            CompletableFuture<Void> gate=new CompletableFuture<>();
            p.files[0].enqueue(List.of(AttachmentBatchQueue.selectFile(p.source)),gate).get(3,TimeUnit.SECONDS);
            p.files[0].pauseBatch().get(3,TimeUnit.SECONDS);
            Object ticket=suspend(p.files[0]);resume(p.files[0],ticket);gate.complete(null);p.files[0].pauseBatch().get(3,TimeUnit.SECONDS);
            check(p.data.get()==0&&p.files[0].batchItems().stream().allMatch(i->i.state().equals("paused")),"Automatic handover resumed a manually paused batch");
        }
        try(Pair p=new Pair()){
            p.start();Object a=suspend(p.files[0]),b=suspend(p.files[1]);p.holdCredits=false;
            resume(p.files[1],b);resume(p.files[0],a);p.verify();
        }
        try(Pair p=new Pair()){
            p.start();Object a=suspend(p.files[0]),b=suspend(p.files[1]);
            p.reconnect();p.holdCredits=false;resume(p.files[1],b);resume(p.files[0],a);p.verify();
        }
        try(Pair p=new Pair()){
            p.dropOffer=true;p.files[0].enqueue(List.of(AttachmentBatchQueue.selectFile(p.source))).get(3,TimeUnit.SECONDS);
            check(p.networkPause.await(3,TimeUnit.SECONDS),"Fixture did not lose the initial offer");
            Object a=suspend(p.files[0]),b=suspend(p.files[1]);p.dropOffer=false;p.holdCredits=false;
            resume(p.files[1],b);resume(p.files[0],a);p.verify();
        }
        try(Pair p=new Pair()){
            p.start();p.failData=true;p.holdCredits=false;
            check(p.heldCredit!=null,"Fixture did not withhold receiver credit");p.engines[0].receive(p.heldCredit,true);
            check(p.networkPause.await(3,TimeUnit.SECONDS),"Fixture did not lose the file wire during DATA");
            Object a=suspend(p.files[0]);
            check(!(Boolean)a.getClass().getMethod("empty").invoke(a),"Wire loss before the route callback forgot the sender's automatic recovery intent");
            Object b=suspend(p.files[1]);p.failData=false;resume(p.files[1],b);resume(p.files[0],a);p.verify();
        }
        try(Pair p=new Pair()){
            p.start();Object a=suspend(p.files[0]),b=suspend(p.files[1]);
            p.files[0].pauseBatch().get(3,TimeUnit.SECONDS);p.holdCredits=false;
            resume(p.files[0],a);resume(p.files[1],b);
            long before=p.data.get();Thread.sleep(100);
            check(p.data.get()==before&&p.delivered.isEmpty(),"The opposite endpoint forced a locally paused sender to resume");
        }
        try(Pair p=new Pair()){
            p.start();Object a=suspend(p.files[0]),b=suspend(p.files[1]);
            String id=p.files[0].batchItems().get(0).info().id;p.files[0].cancel(id,true).get(3,TimeUnit.SECONDS);p.holdCredits=false;
            resume(p.files[1],b);resume(p.files[0],a);Thread.sleep(100);
            check(p.delivered.isEmpty()&&p.files[0].batchItems().get(0).state().equals("canceled"),"A stale recovery callback revived a canceled file");
        }
        System.out.println("Selective FILE recovery: checkpoint resume, manual pause before/during migration, peer STATUS guard and canceled-task safety passed");
    }
    private static Object suspend(ClientAttachmentTransfers files)throws Exception{return ((CompletableFuture<?>)ClientAttachmentTransfers.class.getMethod("suspendForRouteChange").invoke(files)).get(5,TimeUnit.SECONDS);}
    private static void resume(ClientAttachmentTransfers files,Object ticket)throws Exception{((CompletableFuture<?>)ClientAttachmentTransfers.class.getMethod("resumeAfterRouteChange",ticket.getClass()).invoke(files,ticket)).get(5,TimeUnit.SECONDS);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Pair implements AutoCloseable {
        final Path root=Files.createTempDirectory("automatic-file-recovery-"),source=root.resolve("source.bin");
        final AttachmentTransferV2[] engines=new AttachmentTransferV2[2];final ClientAttachmentTransfers[] files=new ClientAttachmentTransfers[2];
        final BlockingQueue<AttachmentRecord> received=new LinkedBlockingQueue<>(),delivered=new LinkedBlockingQueue<>();
        final AtomicLong data=new AtomicLong();final CountDownLatch firstBlock=new CountDownLatch(1);final AtomicReference<Throwable> failure=new AtomicReference<>();
        final byte[] content=new byte[3*TransferLimits.BLOCK_BYTES+17];volatile boolean holdCredits=true,failData,dropOffer;
        final CountDownLatch networkPause=new CountDownLatch(1);volatile TransferPacket heldCredit;
        Pair()throws Exception {
            new Random(71).nextBytes(content);Files.write(source,content);
            for(int i=0;i<2;i++)createPeer(i,"c".repeat(64));
        }
        void reconnect()throws Exception {
            for(ClientAttachmentTransfers file:files)file.shutdownForRouteChange().get(5,TimeUnit.SECONDS);
            for(int i=0;i<2;i++)createPeer(i,"d".repeat(64));
        }
        void createPeer(int i,String generation)throws Exception {
                int to=1-i;final int from=i;
                engines[i]=new AttachmentTransferV2(root.resolve("p"+i),String.valueOf((char)('a'+i)).repeat(64),String.valueOf((char)('a'+to)).repeat(64),generation,false,new AttachmentTransferV2.Wire(){
                    public boolean send(TransferPacket packet){try{
                        if(packet.kind==TransferPacket.Kind.OFFER&&dropOffer)return false;
                        if(packet.kind==TransferPacket.Kind.CREDIT&&holdCredits){heldCredit=packet;return true;}
                        if(packet.kind==TransferPacket.Kind.DATA&&failData)return false;
                        if(packet.kind==TransferPacket.Kind.DATA)data.addAndGet(packet.data.length);
                        engines[to].receive(packet,true);
                        if(from==0&&packet.kind==TransferPacket.Kind.BLOCK_HASH)firstBlock.countDown();return true;
                    }catch(IOException error){failure.compareAndSet(null,error);return false;}}
                    public void abort(){failure.compareAndSet(null,new IOException("Unexpected transfer abort"));}
                    public boolean recoverableDisconnect(){return true;}
                    public boolean restoreOffer(TransferPacket packet){try{AttachmentTransferV2.class.getMethod("receiveRecoveryOffer",TransferPacket.class,boolean.class).invoke(engines[to],packet,true);return true;}catch(Exception error){failure.compareAndSet(null,error);return false;}}
                },record->{if(record.state.equals("received"))received.add(record);if(record.state.equals("delivered"))delivered.add(record);if(from==0&&record.state.equals("paused"))networkPause.countDown();});
                files[i]=new ClientAttachmentTransfers(root.resolve("p"+i),engines[i],saved->new FileAttachmentSource(Paths.get(java.net.URI.create(saved.sourceReference().split("\n",2)[0])),saved.key().sourceGeneration()));
        }
        void start()throws Exception{files[0].enqueue(List.of(AttachmentBatchQueue.selectFile(source))).get(3,TimeUnit.SECONDS);check(firstBlock.await(3,TimeUnit.SECONDS),"Fixture did not transfer a verified block");}
        void verify()throws Exception {
            AttachmentRecord saved=received.poll(5,TimeUnit.SECONDS),ack=delivered.poll(5,TimeUnit.SECONDS);
            check(failure.get()==null&&saved!=null&&ack!=null,"Recovered transfer did not complete: "+failure.get());
            check(saved.info.id.equals(ack.info.id)&&saved.info.hash.equals(ack.info.hash),"Recovery changed task identity or saved digest");
            check(Arrays.equals(content,Files.readAllBytes(AttachmentTransfer.file(root.resolve("p1"),saved.info))),"Recovered file bytes changed");
            check(received.isEmpty()&&delivered.isEmpty(),"Recovery duplicated a saved receipt");
        }
        public void close()throws Exception{for(ClientAttachmentTransfers file:files)if(file!=null)file.shutdown().get(5,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
