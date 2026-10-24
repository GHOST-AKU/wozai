package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A stopped recovery must not supply the next file's checkpoint snapshot. */
public final class FileRecoveryLeaseTests {
    public static void main(String[] args)throws Exception {
        for(int abandonedSide=0;abandonedSide<2;abandonedSide++)for(boolean batched:new boolean[]{false,true})
            nextFileAfterAbandonedFreeze(abandonedSide,batched);
        System.out.println("Recovery leases: abandoned sender/receiver freeze, manual completion, next direct/batch file resumes with original bytes and one receipt passed");
    }
    private static void nextFileAfterAbandonedFreeze(int abandonedSide,boolean batched)throws Exception {
        try(Pair p=new Pair()){
            String first=p.offer("first.bin",batched);
            p.startReceiving(first);
            p.files[abandonedSide].suspendForRouteChange().get(3,TimeUnit.SECONDS);
            p.engines[1-abandonedSide].pauseOnDisconnect().get(3,TimeUnit.SECONDS);p.records.clear();
            // The route owner stopped without consuming its freeze result. The user resumes manually.
            p.files[0].resume(first,true).get(3,TimeUnit.SECONDS);p.verify(first);p.records.clear();
            String second=p.offer("second.bin",batched);
            p.startReceiving(second);
            FileRouteResume a=p.files[0].suspendForRouteChange().get(3,TimeUnit.SECONDS),b=p.files[1].suspendForRouteChange().get(3,TimeUnit.SECONDS);p.records.clear();
            p.files[1].resumeAfterRouteChange(b).get(3,TimeUnit.SECONDS);p.files[0].resumeAfterRouteChange(a).get(3,TimeUnit.SECONDS);
            p.verify(second);
        }
    }
    private interface Check{boolean test()throws Exception;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Pair implements AutoCloseable {
        final Path root=Files.createTempDirectory("file-recovery-lease-"),source=root.resolve("source.bin");
        final byte[] bytes=new byte[256*1024];final Queue<Runnable> records=new ConcurrentLinkedQueue<>();
        final AttachmentTransferV2[] engines=new AttachmentTransferV2[2];final ClientAttachmentTransfers[] files=new ClientAttachmentTransfers[2];
        final Map<String,AttachmentRecord> sent=new ConcurrentHashMap<>(),received=new ConcurrentHashMap<>();
        final Map<String,AtomicInteger> savedCount=new ConcurrentHashMap<>(),receiptCount=new ConcurrentHashMap<>();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        Pair()throws Exception {
            new Random(93).nextBytes(bytes);Files.write(source,bytes);String generation=UUID.randomUUID().toString();
            for(int side=0;side<2;side++){
                final int from=side,to=1-side;
                engines[side]=new AttachmentTransferV2(root.resolve("p"+side),String.valueOf((char)('a'+side)).repeat(64),String.valueOf((char)('a'+to)).repeat(64),generation,false,new AttachmentTransferV2.Wire(){
                    public boolean highBandwidth(){return true;}
                    public boolean send(TransferPacket packet){records.add(()->{try{engines[to].receive(packet,true);}catch(IOException error){failure.compareAndSet(null,error);}});return true;}
                    public boolean restoreOffer(TransferPacket packet){records.add(()->{try{engines[to].receiveRecoveryOffer(packet,true);}catch(IOException error){failure.compareAndSet(null,error);}});return true;}
                    public void abort(){failure.compareAndSet(null,new IOException("Unexpected file pipeline abort at "+from));}
                    public void abort(Throwable error){failure.compareAndSet(null,error);}
                },record->{
                    (record.outgoing?sent:received).put(record.info.id,record);
                    if(record.state.equals("received"))savedCount.computeIfAbsent(record.info.id,key->new AtomicInteger()).incrementAndGet();
                    if(record.state.equals("delivered"))receiptCount.computeIfAbsent(record.info.id,key->new AtomicInteger()).incrementAndGet();
                });
                files[side]=new ClientAttachmentTransfers(root.resolve("p"+side),engines[side],checkpoint->new FileAttachmentSource(Paths.get(java.net.URI.create(checkpoint.sourceReference().split("\n",2)[0])),checkpoint.key().sourceGeneration()));
            }
        }
        void until(Check condition,boolean deliver,String message)throws Exception {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<deadline){if(deliver)drain();if(failure.get()!=null)throw new AssertionError(message,failure.get());if(condition.test())return;Thread.sleep(2);}
            throw new AssertionError(message+" sender="+engines[0].diagnostics()+" receiver="+engines[1].diagnostics());
        }
        void drain(){Runnable next;while((next=records.poll())!=null)next.run();}
        void fence()throws Exception {
            var field=AttachmentTransferV2.class.getDeclaredField("actor");field.setAccessible(true);
            for(AttachmentTransferV2 engine:engines)((ExecutorService)field.get(engine)).submit(()->{}).get(3,TimeUnit.SECONDS);
        }
        String offer(String name,boolean batched)throws Exception {
            if(!batched)return files[0].offer(new FileAttachmentSource(source),name,"application/octet-stream").get(3,TimeUnit.SECONDS);
            String batch=files[0].enqueue(List.of(AttachmentBatchQueue.selectFile(source))).get(3,TimeUnit.SECONDS);
            return files[0].batchItems().stream().filter(item->item.batchId().equals(batch)).findFirst().orElseThrow().info().id;
        }
        void startReceiving(String id)throws Exception {
            // Deliver only OFFER/ACCEPT; in-flight data is lost when the test retires this lane.
            until(()->!records.isEmpty(),false,"Missing OFFER");records.remove().run();
            until(()->received.containsKey(id)&&!records.isEmpty(),false,"Missing ACCEPT");records.remove().run();fence();
        }
        void verify(String id)throws Exception {
            until(()->sent.containsKey(id)&&sent.get(id).state.equals("delivered"),true,"Next file did not resume after an abandoned recovery");
            drain();fence();
            AttachmentRecord a=sent.get(id),b=received.get(id);
            check(a.info.id.equals(b.info.id)&&a.info.hash.equals(b.info.hash),"Recovery changed file identity or digest");
            check(Arrays.equals(bytes,Files.readAllBytes(AttachmentTransfer.file(root.resolve("p1"),b.info))),"Recovery changed received bytes");
            check(savedCount.get(id).get()==1&&receiptCount.get(id).get()==1,"Recovery duplicated a saved receipt");
        }
        public void close()throws Exception {
            for(ClientAttachmentTransfers file:files)if(file!=null)file.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
