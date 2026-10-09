package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Reproduces slow durable-history storage without weakening content/checkpoint commits. */
public final class AttachmentFeedbackTests {
    public static void main(String[] args)throws Exception {slowStorage();coalescedHistory();resumeFeedback();}
    private static void slowStorage()throws Exception {
        Path root=Files.createTempDirectory("attachment-feedback-");
        AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        AtomicInteger activeHistoryWrites=new AtomicInteger();
        CompletableFuture<AttachmentRecord> received=new CompletableFuture<>(),delivered=new CompletableFuture<>();
        List<AttachmentRecord> live=new CopyOnWriteArrayList<>();
        try {
            peers[0]=new AttachmentTransferV2(root.resolve("a"),"a".repeat(64),"b".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,1),r->{if(r.state.equals("delivered"))delivered.complete(r);});
            peers[1]=new AttachmentTransferV2(root.resolve("b"),"b".repeat(64),"a".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,0),new AttachmentTransferV2.Listener(){
                public void changed(AttachmentRecord r)throws IOException {
                    if(r.state.equals("transferring")){
                        activeHistoryWrites.incrementAndGet();
                        try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    }
                    if(r.state.equals("received"))received.complete(r);
                }
                public void progress(AttachmentRecord r,TransferProgress p){live.add(r);}
            });
            byte[] bytes=new byte[8*TransferLimits.DATA_BYTES];new Random(71).nextBytes(bytes);
            Path source=root.resolve("payload.bin");Files.write(source,bytes);
            long begin=System.nanoTime();peers[0].offer(new FileAttachmentSource(source),"payload.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            AttachmentRecord saved=received.get(8,TimeUnit.SECONDS),ack=delivered.get(8,TimeUnit.SECONDS);
            double seconds=(System.nanoTime()-begin)/1e9;System.out.println("Synthetic 300 ms history save: "+activeHistoryWrites.get()+" active writes, "+seconds+"s complete, "+(bytes.length/(1024.0*1024)/seconds)+" MiB/s (controlled storage, not phone speed)");
            if(activeHistoryWrites.get()!=1)throw new AssertionError("Slow history save made every DATA packet persist progress: "+activeHistoryWrites.get()+" writes; expected only the transfer-state transition before the final commit");
            if(!Arrays.equals(bytes,Files.readAllBytes(AttachmentTransfer.file(root.resolve("b"),saved.info))))throw new AssertionError("Content changed");
            if(!saved.info.hash.equals(AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))||!saved.info.hash.equals(ack.info.hash))throw new AssertionError("Saved receipt digest mismatch");
            if(live.stream().noneMatch(r->r.state.equals("transferring")))throw new AssertionError("UI lost live transfer feedback");
            System.out.println("Slow-storage feedback: progress avoids per-packet durable-history writes; final content/history receipt verified");
        }finally{
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    private static void coalescedHistory()throws Exception {
        Path root=Files.createTempDirectory("coalesced-history-");AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        AtomicLong previousDurable=new AtomicLong(-1);AtomicInteger redundantSaves=new AtomicInteger(),updates=new AtomicInteger();
        CompletableFuture<AttachmentRecord> received=new CompletableFuture<>(),delivered=new CompletableFuture<>();
        try {
            peers[0]=new AttachmentTransferV2(root.resolve("a"),"a".repeat(64),"b".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,1),r->{if(r.state.equals("delivered"))delivered.complete(r);});
            peers[1]=new AttachmentTransferV2(root.resolve("b"),"b".repeat(64),"a".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,0),new AttachmentTransferV2.Listener(){
                public void changed(AttachmentRecord r)throws IOException {
                    if(r.state.equals("transferring")){
                        long durable=new TransferCheckpointStore(root.resolve("b/.tasks-v2")).list().get(0).durableOffset();
                        long previous=previousDurable.getAndSet(durable);if(previous==durable)redundantSaves.incrementAndGet();
                    }
                    if(r.state.equals("received"))received.complete(r);
                }
                public void progress(AttachmentRecord r,TransferProgress p){if(r.state.equals("transferring"))updates.incrementAndGet();}
            });
            Path file=root.resolve("source.bin");Files.write(file,new byte[8*TransferLimits.BLOCK_BYTES]);
            peers[0].offer(slowSource(new FileAttachmentSource(file),new AtomicBoolean()),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            received.get(10,TimeUnit.SECONDS);delivered.get(10,TimeUnit.SECONDS);
            if(redundantSaves.get()>0)throw new AssertionError("UI feedback persisted history without a new durable checkpoint: "+redundantSaves.get()+" redundant saves");
            if(updates.get()<2)throw new AssertionError("Coalescing removed periodic live progress");
            System.out.println("Live transfer updates continue while durable history is coalesced to state/checkpoint changes");
        }finally{
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    private static void resumeFeedback()throws Exception {
        Path root=Files.createTempDirectory("resume-feedback-");
        AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        CompletableFuture<AttachmentRecord> paused=new CompletableFuture<>(),received=new CompletableFuture<>(),delivered=new CompletableFuture<>();
        AtomicLong sentBytes=new AtomicLong();AtomicInteger checkingEvents=new AtomicInteger();AtomicBoolean checking=new AtomicBoolean();
        try {
            Path file=root.resolve("source.bin");byte[] bytes=new byte[8*TransferLimits.BLOCK_BYTES+17];new Random(99).nextBytes(bytes);Files.write(file,bytes);
            AttachmentTransferV2.Listener sender=new AttachmentTransferV2.Listener(){
                public void changed(AttachmentRecord r){checking.set(r.state.equals("checking"));if(r.state.equals("delivered"))delivered.complete(r);}
                public void progress(AttachmentRecord r,TransferProgress p){if(r.state.equals("checking"))checkingEvents.incrementAndGet();}
            };
            peers[0]=new AttachmentTransferV2(root.resolve("a"),"a".repeat(64),"b".repeat(64),AttachmentV2Tests.CONNECTION,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket p){try{if(p.kind==TransferPacket.Kind.DATA)sentBytes.addAndGet(p.data.length);peers[1].receive(p,true);return true;}catch(IOException e){return false;}}
                public void abort(){throw new AssertionError("Sender aborted during same-session resume");}
            },sender);
            peers[1]=new AttachmentTransferV2(root.resolve("b"),"b".repeat(64),"a".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,0),r->{if(r.state.equals("paused"))paused.complete(r);if(r.state.equals("received"))received.complete(r);});
            FileAttachmentSource original=new FileAttachmentSource(file);String generation=original.generation();
            String id=peers[0].offer(slowSource(original,checking),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(sentBytes.get()<2L*TransferLimits.BLOCK_BYTES&&System.nanoTime()<until)Thread.sleep(2);
            if(sentBytes.get()<2L*TransferLimits.BLOCK_BYTES)throw new AssertionError("No prefix to pause");
            peers[0].pause(id,true).get(5,TimeUnit.SECONDS);paused.get(5,TimeUnit.SECONDS);
            TransferTaskKey key=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,id,generation);
            peers[0].resume(key,slowSource(new FileAttachmentSource(file,generation),checking)).get(5,TimeUnit.SECONDS);
            AttachmentRecord end=received.get(10,TimeUnit.SECONDS);delivered.get(10,TimeUnit.SECONDS);
            if(!Arrays.equals(bytes,Files.readAllBytes(AttachmentTransfer.file(root.resolve("b"),end.info))))throw new AssertionError("Same-session resume changed content");
            if(checkingEvents.get()<2)throw new AssertionError("Prefix verification provides only a static checking label: "+checkingEvents.get()+" updates");
            System.out.println("Same-session pause/resume: full content verified with continuing prefix-check feedback");
        }finally{
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    private static AttachmentSource slowSource(FileAttachmentSource file,AtomicBoolean checking){return new AttachmentSource(){
        public long size(){return file.size();}public boolean seekable(){return true;}public String generation(){return file.generation();}
        public String persistentReference(){return file.persistentReference();}public void verifyUnchanged()throws IOException{file.verifyUnchanged();}public void close()throws IOException{file.close();}
        public InputStream open(long offset)throws IOException{return new FilterInputStream(file.open(offset)){
            public int read(byte[] bytes,int from,int count)throws IOException {try{Thread.sleep(checking.get()?8:2);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return in.read(bytes,from,count);}
        };}
    };}
    private static AttachmentTransferV2.Wire wire(AttachmentTransferV2[] peers,int index){return new AttachmentTransferV2.Wire(){
        public boolean send(TransferPacket p){try{peers[index].receive(p,true);return true;}catch(IOException e){return false;}}
        public void abort(Throwable e){throw new AssertionError("Unexpected pipeline failure",e);}
        public void abort(){throw new AssertionError("Unexpected pipeline failure");}
    };}
}
