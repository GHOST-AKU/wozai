package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class AttachmentResumeTests {
    private static int checks;
    private static final String ID="11111111-1111-1111-1111-111111111111",GEN="22222222-2222-2222-2222-222222222222";
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("resume-store-");
        try {
            TransferTaskKey key=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.RECEIVE,ID,GEN);
            TransferTaskKey other=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,ID,GEN);
            check(!key.fileName().equals(other.fileName()),"Directions share a task key");
            TransferCheckpointStore store=new TransferCheckpointStore(root.resolve("journal"));
            AttachmentInfo info=AttachmentInfo.v2(ID,"data.bin","application/octet-stream",TransferLimits.BLOCK_BYTES+3,null,1);
            TransferCheckpoint state=new TransferCheckpoint(key,info,"",TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferCheckpoint.State.PAUSED,System.currentTimeMillis());
            byte[] block=new byte[TransferLimits.BLOCK_BYTES];new Random(4).nextBytes(block);byte[] hash=MessageDigest.getInstance("SHA-256").digest(block);
            Path content=root.resolve("content.bin");Files.write(content,block);
            store.appendBlock(key,TransferLimits.BLOCK_BYTES,hash);store.checkpoint(state);
            TransferCheckpoint loaded=store.load(key).orElseThrow();check(loaded.durableOffset()==TransferLimits.BLOCK_BYTES,"Checkpoint offsets changed");
            check(store.load(other).isEmpty(),"Another direction recovered this task");
            check(Arrays.equals(store.verifyPrefix(loaded,content).sha256(),hash),"Verified prefix differs");
            try(RandomAccessFile file=new RandomAccessFile(content.toFile(),"rw")){file.seek(2);file.write(42);}
            rejects(()->store.verifyPrefix(loaded,content));
            Path journal=root.resolve("journal").resolve(key.fileName()+".checkpoint");byte[] bytes=Files.readAllBytes(journal);bytes[bytes.length-1]^=1;Files.write(journal,bytes);
            rejects(()->store.load(key));Files.delete(journal);store.checkpoint(state);
            store.cancel(key);check(store.load(key).orElseThrow().state()==TransferCheckpoint.State.CANCELED,"Cancellation tombstone missing");
            TransferCheckpoint revived=new TransferCheckpoint(key,info,"",0,0,0,TransferCheckpoint.State.ACTIVE,System.currentTimeMillis());
            rejects(()->store.checkpoint(revived));
            TransferStorageBudget budget=new TransferStorageBudget(root.resolve("budget"),100L*1024*1024);
            try(TransferStorageBudget.Reservation reservation=budget.reserve(key,80L*1024*1024,0)) {
                rejects(()->budget.reserve(other,30L*1024*1024,0));
                try(OutputStream output=Files.newOutputStream(root.resolve("budget/stored.part"))){for(int i=0;i<10;i++)output.write(block);}
                reservation.written(10L*1024*1024);
                check(budget.reservedBytes()==70L*1024*1024,"Written bytes remain reserved twice");
            }
            check(budget.reservedBytes()==0,"Storage reservation leaked");
            Path restart=root.resolve("restart");Files.createDirectories(restart);Path source=restart.resolve("source.bin");
            try(OutputStream output=Files.newOutputStream(source)){output.write(block);output.write(block);output.write(new byte[]{4,5,6});}
            String generation,id;
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(restart,false,AttachmentV2Tests.CONNECTION)) {
                pair.holdCredit=true;FileAttachmentSource input=new FileAttachmentSource(source);generation=input.generation();
                id=pair.a.offer(input,"source.bin","application/octet-stream").get(3,java.util.concurrent.TimeUnit.SECONDS);
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                while(pair.dataBytes.get()<TransferLimits.BLOCK_BYTES&&System.nanoTime()<deadline)Thread.sleep(5);
                pair.a.pause(id,true);AttachmentV2Tests.waitFor(pair.aRecords,"paused");AttachmentV2Tests.waitFor(pair.bRecords,"paused");
            }
            AttachmentTransfer.clean(restart.resolve("b"),Set.of());
            TransferTaskKey sendKey=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,id,generation);
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(restart,false,"d".repeat(64))) {
                pair.a.resume(sendKey,new FileAttachmentSource(source,generation)).get(3,java.util.concurrent.TimeUnit.SECONDS);
                AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                check(pair.dataBytes.get()==TransferLimits.BLOCK_BYTES+3,"Resume retransmitted a saved prefix");
                check(Files.size(AttachmentTransfer.file(restart.resolve("b"),received.info))==2L*TransferLimits.BLOCK_BYTES+3,"Resumed content truncated");
            }
            lostReceipt(root,false);lostReceipt(root,true);corruptRestart(root);duplicateEnd(root);sourceChange(root);canceledRestart(root);singleWriter(root);
            crashBoundary(root,false,0);crashBoundary(root,false,TransferLimits.BLOCK_BYTES);crashBoundary(root,true,2L*TransferLimits.BLOCK_BYTES+3);
            ownedSource(root);
            ownedPause(root);
            pauseDuringPrefix(root);
            receiptWithoutSource(root);
            revokedPending(root);
            bridgeRejectsForeignRoot(root);
            cancelPausedPeer(root);
            AttachmentInfo pending=AttachmentInfo.v2(ID,"data.bin","application/octet-stream",10,null,1);
            AttachmentRecord paused=new AttachmentRecord(pending,true,"paused",7);
            check(paused.mayReplace(new AttachmentRecord(pending,true,"checking",0)),"Paused history cannot resume");
            check(!new AttachmentRecord(pending,true,"canceled",7).mayReplace(new AttachmentRecord(pending,true,"checking",0)),"Canceled history resurrected");
            AttachmentRecord legacyPreparing=new AttachmentRecord(new AttachmentInfo(ID,"data.bin","application/octet-stream",0,"0".repeat(64),1),true,"preparing",0);
            check(legacyPreparing.mayReplace(new AttachmentRecord(new AttachmentInfo(ID,"data.bin","application/octet-stream",10,"1".repeat(64),1),true,"offered",0)),"Legacy preparation cannot finalize metadata");
            System.out.println("AttachmentResumeTests: "+checks+" checkpoint/integrity/budget checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static TransferTaskKey sender(String id,String generation){return new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,id,generation);}
    private static void bridgeRejectsForeignRoot(Path root)throws Exception {
        Path directory=root.resolve("foreign-root");Files.createDirectories(directory);Path original=directory.resolve("source.bin");Files.write(original,new byte[10]);
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            TransferTaskKey foreign=new TransferTaskKey("a".repeat(64),"e".repeat(64),TransferTaskKey.Direction.SEND,ID,GEN);
            AttachmentInfo info=AttachmentInfo.v2(ID,"data.bin","application/octet-stream",10,null,1);
            new TransferCheckpointStore(directory.resolve("a/.tasks-v2")).checkpoint(new TransferCheckpoint(foreign,info,"foreign provider URI",0,0,0,TransferCheckpoint.State.PAUSED,System.currentTimeMillis()));
            java.util.concurrent.atomic.AtomicInteger opened=new java.util.concurrent.atomic.AtomicInteger();
            ClientAttachmentTransfers bridge=new ClientAttachmentTransfers(directory.resolve("a"),pair.a,saved->{opened.incrementAndGet();return new FileAttachmentSource(original,GEN);});
            try{bridge.resume(ID,true).get(3,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("Foreign task resumed");}catch(IOException|java.util.concurrent.ExecutionException expected){}
            check(opened.get()==0,"A different root's task opened its persisted source before identity rejection");
        }
    }
    private static void cancelPausedPeer(Path root)throws Exception {
        Path directory=root.resolve("cancel-paused-peer");Files.createDirectories(directory);Path original=directory.resolve("source.bin");Files.write(original,new byte[2*TransferLimits.BLOCK_BYTES]);
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;String id=pair.a.offer(new FileAttachmentSource(original),"data.bin","application/octet-stream").get();
            AttachmentV2Tests.waitFor(pair.aRecords,"transferring");pair.a.pause(id,true).get();AttachmentV2Tests.waitFor(pair.aRecords,"paused");AttachmentV2Tests.waitFor(pair.bRecords,"paused");
            pair.a.cancelAll().get();AttachmentV2Tests.waitFor(pair.aRecords,"canceled");AttachmentV2Tests.waitFor(pair.bRecords,"canceled");
            check(Files.exists(original),"Cancel all deleted the selected original");
            check(new TransferCheckpointStore(directory.resolve("b/.tasks-v2")).list().stream().allMatch(saved->saved.state()==TransferCheckpoint.State.CANCELED),"Peer retained a resumable canceled task");
        }
    }
    private static void ownedSource(Path root)throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()) {
            Path originals=pair.root.resolve("selected.bin"),snapshots=pair.root.resolve("a/.sources-v2");Files.write(originals,new byte[2*TransferLimits.BLOCK_BYTES]);Files.createDirectories(snapshots);
            OwnedSnapshotSource owned;try(FileAttachmentSource selected=new FileAttachmentSource(originals)){owned=new OwnedSnapshotSource(AttachmentSourceSnapshot.create(selected,snapshots,3L*TransferLimits.BLOCK_BYTES),snapshots);}
            Path copy=owned.path();pair.holdCredit=true;String id=pair.a.offer(owned,"data.bin","application/octet-stream").get();
            AttachmentV2Tests.waitFor(pair.aRecords,"transferring");pair.a.cancel(id,true);AttachmentV2Tests.waitFor(pair.aRecords,"canceled");AttachmentV2Tests.waitFor(pair.bRecords,"canceled");
            check(!Files.exists(copy),"Canceled transfer retained its private source snapshot");check(Files.exists(originals),"Cancellation deleted a user-selected original");
        }
    }
    private static void ownedPause(Path root)throws Exception {
        Path directory=root.resolve("owned-pause");Files.createDirectories(directory);Path original=directory.resolve("selected.bin");Files.write(original,new byte[2*TransferLimits.BLOCK_BYTES]);Path copies=directory.resolve("a/.sources-v2");String id;Path copy;
        TransferStorageBudget budget=new TransferStorageBudget(directory.resolve("a"),32L*TransferLimits.BLOCK_BYTES);
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;FileAttachmentSource source=new FileAttachmentSource(original);
            OwnedSnapshotSource owned=AttachmentSourceSnapshot.owned(source,copies,budget,sender(UUID.randomUUID().toString(),source.generation()));copy=owned.path();
            id=pair.a.offer(owned,"data.bin","application/octet-stream").get();while(pair.dataBytes.get()<TransferLimits.BLOCK_BYTES)Thread.sleep(5);
            pair.a.pause(id,true).get();AttachmentV2Tests.waitFor(pair.bRecords,"paused");check(Files.exists(copy),"Pause discarded a resumable source");
        }
        check(budget.reservedBytes()==0,"Snapshot preparation retained its temporary reservation");AttachmentTransfer.clean(directory.resolve("a"),Set.of());check(Files.exists(copy),"Startup cleanup deleted a referenced snapshot");
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            ClientAttachmentTransfers adapter=new ClientAttachmentTransfers(directory.resolve("a"),pair.a,value->OwnedSnapshotSource.restore(copies,value.sourceReference(),value.key().sourceGeneration()));
            adapter.cancel(id,true).get();AttachmentV2Tests.waitFor(pair.bRecords,"canceled");check(!Files.exists(copy)&&Files.exists(original),"Canceling a paused task damaged ownership boundaries");
        }
    }
    private static void pauseDuringPrefix(Path root)throws Exception {
        Path directory=root.resolve("pause-prefix");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[2*TransferLimits.BLOCK_BYTES+3]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;FileAttachmentSource source=new FileAttachmentSource(file);generation=source.generation();id=pair.a.offer(source,"data.bin","application/octet-stream").get();while(pair.dataBytes.get()<TransferLimits.BLOCK_BYTES)Thread.sleep(5);
            pair.a.pause(id,true).get();AttachmentV2Tests.waitFor(pair.bRecords,"paused");
        }
        TransferTaskKey key=sender(id,generation);TransferCheckpointStore store=new TransferCheckpointStore(directory.resolve("a/.tasks-v2"));TransferCheckpoint old=store.load(key).orElseThrow();
        store.checkpoint(new TransferCheckpoint(key,old.info(),old.sourceReference(),TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferCheckpoint.State.PAUSED,System.currentTimeMillis()));
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            FileAttachmentSource original=new FileAttachmentSource(file,generation);AttachmentSource blocked=new AttachmentSource(){
                public long size(){return original.size();}public String generation(){return original.generation();}public boolean seekable(){return true;}public String persistentReference(){return original.persistentReference();}public void verifyUnchanged()throws IOException{original.verifyUnchanged();}
                public InputStream open(long offset)throws IOException{return new FilterInputStream(original.open(offset)){public int read(byte[] bytes,int from,int count)throws IOException{entered.countDown();try{if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("Prefix read did not cancel");}catch(InterruptedException error){throw new IOException(error);}throw new IOException("Canceled prefix read");}};}
                public void close()throws IOException{release.countDown();original.close();}
            };
            pair.a.resume(key,blocked).get();check(entered.await(3,java.util.concurrent.TimeUnit.SECONDS),"Resume never verified the source prefix");pair.a.pause(id,true).get(3,java.util.concurrent.TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.aRecords,"paused");
        }
        check(Files.size(directory.resolve("a/.tasks-v2").resolve(key.fileName()+".blocks"))>=40,"Canceled rehash destroyed the last committed source index");
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"e".repeat(64))) {
            pair.a.resume(key,new FileAttachmentSource(file,generation)).get();AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(pair.dataBytes.get()==TransferLimits.BLOCK_BYTES+3,"Canceled rehash prevented a safe retry");
        }
    }
    private static void revokedPending(Path root)throws Exception {
        Path directory=root.resolve("revoked");Files.createDirectories(directory);String id=UUID.randomUUID().toString();
        TransferTaskKey pending=sender(id,GEN);AttachmentInfo info=AttachmentInfo.v2(id,"pending.bin","application/octet-stream",TransferLimits.BLOCK_BYTES,null,1);
        TransferCheckpointStore journal=new TransferCheckpointStore(directory.resolve(".tasks-v2"));
        journal.checkpoint(new TransferCheckpoint(pending,info,"",0,0,0,TransferCheckpoint.State.PAUSED,System.currentTimeMillis()));
        Path partial=directory.resolve(pending.fileName()+".part");Files.write(partial,new byte[]{1});
        String doneId=UUID.randomUUID().toString();AttachmentInfo done=AttachmentInfo.v2(doneId,"done.bin","application/octet-stream",0,AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest()),1);
        TransferTaskKey completed=sender(doneId,GEN);journal.checkpoint(new TransferCheckpoint(completed,done,"",0,0,0,TransferCheckpoint.State.COMPLETE,System.currentTimeMillis()));Path content=AttachmentTransfer.file(directory,done,true);Files.createFile(content);
        List<AttachmentRecord> canceled=AttachmentTransferV2.cancelPending(directory);
        check(canceled.size()==1&&canceled.get(0).state.equals("canceled"),"Revocation did not terminate pending history");
        check(journal.load(pending).orElseThrow().state()==TransferCheckpoint.State.CANCELED&&!Files.exists(partial),"Revocation retained a resumable task");
        check(journal.load(completed).orElseThrow().state()==TransferCheckpoint.State.COMPLETE&&Files.exists(content),"Revocation deleted completed content");
    }
    private static void receiptWithoutSource(Path root)throws Exception {
        Path directory=root.resolve("receipt-no-source");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[TransferLimits.BLOCK_BYTES+3]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.dropComplete=true;FileAttachmentSource source=new FileAttachmentSource(file);generation=source.generation();id=pair.a.offer(source,"data.bin","application/octet-stream").get();AttachmentV2Tests.waitFor(pair.bRecords,"received");
        }
        Files.delete(file);
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            pair.a.resume(sender(id,generation),null).get();AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(pair.dataBytes.get()==0,"Lost receipt needed the original after verified delivery");
        }
    }
    private static void crashBoundary(Path root,boolean renamed,long durable)throws Exception {
        Path directory=root.resolve("crash-"+renamed+"-"+durable);Files.createDirectories(directory);Path source=directory.resolve("source.bin");byte[] bytes=new byte[2*TransferLimits.BLOCK_BYTES+3];new Random(8).nextBytes(bytes);Files.write(source,bytes);
        String generation=UUID.randomUUID().toString(),id=UUID.randomUUID().toString();TransferTaskKey send=sender(id,generation),receive=new TransferTaskKey("b".repeat(64),"a".repeat(64),TransferTaskKey.Direction.RECEIVE,id,generation);
        AttachmentInfo info=AttachmentInfo.v2(id,"data.bin","application/octet-stream",bytes.length,renamed?AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes)):null,1);
        TransferCheckpointStore a=new TransferCheckpointStore(directory.resolve("a/.tasks-v2")),b=new TransferCheckpointStore(directory.resolve("b/.tasks-v2"));
        try(FileAttachmentSource input=new FileAttachmentSource(source,generation)){a.checkpoint(new TransferCheckpoint(send,info,input.persistentReference(),0,0,0,TransferCheckpoint.State.ACTIVE,System.currentTimeMillis()));}
        Path content=directory.resolve("b").resolve(receive.fileName()+".part");Files.write(content,bytes);
        for(int start=0;start<bytes.length;start+=TransferLimits.BLOCK_BYTES){int end=Math.min(start+TransferLimits.BLOCK_BYTES,bytes.length);b.appendBlock(receive,end,MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(bytes,start,end)));}
        // END persists the sender's final hash only after its complete block index is forced.
        if(renamed)for(int start=0;start<bytes.length;start+=TransferLimits.BLOCK_BYTES){int end=Math.min(start+TransferLimits.BLOCK_BYTES,bytes.length);a.appendBlock(send,end,MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(bytes,start,end)));}
        try(java.nio.channels.FileChannel file=java.nio.channels.FileChannel.open(content,StandardOpenOption.WRITE)){file.force(true);}
        b.checkpoint(new TransferCheckpoint(receive,info,"",durable,durable,durable,TransferCheckpoint.State.ACTIVE,System.currentTimeMillis()));
        if(renamed)Files.move(content,AttachmentTransfer.file(directory.resolve("b"),info),StandardCopyOption.ATOMIC_MOVE);
        AttachmentTransfer.clean(directory.resolve("b"),Set.of());
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            pair.a.resume(send,new FileAttachmentSource(source,generation)).get();AttachmentRecord saved=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
            check(pair.dataBytes.get()==bytes.length-durable,"Resume used uncommitted apparent file length");
            check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(directory.resolve("b"),saved.info)),bytes),"Crash recovery changed content");
        }
    }
    private static void singleWriter(Path root)throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()) {
            rejects(()->{try(AttachmentTransferV2 duplicate=new AttachmentTransferV2(pair.root.resolve("b"),"b".repeat(64),"a".repeat(64),"d".repeat(64),false,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){return true;}public void abort(){}},record->{})) {}});
            TransferPacket stale=TransferPacket.control(TransferPacket.Kind.STATUS,ID,GEN,"d".repeat(64),true,0,0,0,null,"");
            rejects(()->pair.b.receive(stale,true));rejects(()->pair.b.receive(TransferPacket.control(TransferPacket.Kind.STATUS,ID,GEN,AttachmentV2Tests.CONNECTION,true,0,0,0,null,""),false));
        }
    }
    private static void lostReceipt(Path root,boolean historyFailure)throws Exception {
        Path directory=root.resolve(historyFailure?"history-failed":"receipt-lost");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[TransferLimits.BLOCK_BYTES+7]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.dropComplete=!historyFailure;pair.failSavedHistory=historyFailure;FileAttachmentSource input=new FileAttachmentSource(file);generation=input.generation();
            id=pair.a.offer(input,"data.bin","application/octet-stream").get(3,java.util.concurrent.TimeUnit.SECONDS);
            if(historyFailure){AttachmentV2Tests.waitFor(pair.bRecords,"verifying");long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(!Files.exists(directory.resolve("b/in-"+id+".bin"))&&System.nanoTime()<deadline)Thread.sleep(5);}
            else AttachmentV2Tests.waitFor(pair.bRecords,"received");
        }
        AttachmentTransfer.clean(directory.resolve("b"),Set.of());
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            pair.a.resume(sender(id,generation),new FileAttachmentSource(file,generation)).get(3,java.util.concurrent.TimeUnit.SECONDS);
            AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(pair.dataBytes.get()==0,"Receipt recovery retransmitted saved content");
        }
    }
    private static void corruptRestart(Path root)throws Exception {
        Path directory=root.resolve("corrupt-restart");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[2*TransferLimits.BLOCK_BYTES]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;FileAttachmentSource source=new FileAttachmentSource(file);generation=source.generation();id=pair.a.offer(source,"data.bin","application/octet-stream").get();
            while(pair.dataBytes.get()<TransferLimits.BLOCK_BYTES)Thread.sleep(5);pair.a.pause(id,true);AttachmentV2Tests.waitFor(pair.bRecords,"paused");
        }
        TransferTaskKey receive=new TransferTaskKey("b".repeat(64),"a".repeat(64),TransferTaskKey.Direction.RECEIVE,id,generation);
        Path partial=directory.resolve("b").resolve(receive.fileName()+".part");try(RandomAccessFile changed=new RandomAccessFile(partial.toFile(),"rw")){changed.write(1);}
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            pair.a.resume(sender(id,generation),new FileAttachmentSource(file,generation)).get();AttachmentV2Tests.waitFor(pair.bRecords,"failed");AttachmentV2Tests.waitFor(pair.aRecords,"failed");check(pair.dataBytes.get()==0,"Corrupt prefix permitted resumed data");
        }
    }
    private static void duplicateEnd(Path root)throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()) {
            Path file=pair.root.resolve("source.bin");Files.write(file,new byte[3]);pair.a.offer(new FileAttachmentSource(file),"data.bin","application/octet-stream").get();
            AttachmentV2Tests.waitFor(pair.aRecords,"delivered");pair.b.receive(pair.lastEnd,true);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3);while(pair.completeCount.get()<2&&System.nanoTime()<deadline)Thread.sleep(5);
            check(pair.completeCount.get()==2,"Duplicate END did not reproduce the durable receipt");
        }
    }
    private static void sourceChange(Path root)throws Exception {
        Path directory=root.resolve("changed-source");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[2*TransferLimits.BLOCK_BYTES]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;FileAttachmentSource input=new FileAttachmentSource(file);generation=input.generation();id=pair.a.offer(input,"data.bin","application/octet-stream").get();
            while(pair.dataBytes.get()<TransferLimits.BLOCK_BYTES)Thread.sleep(5);pair.a.pause(id,true);AttachmentV2Tests.waitFor(pair.bRecords,"paused");
        }
        try(RandomAccessFile changed=new RandomAccessFile(file.toFile(),"rw")){changed.write(2);}
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            try{pair.a.resume(sender(id,generation),new FileAttachmentSource(file,generation)).get(3,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("Changed source resumed");}catch(java.util.concurrent.ExecutionException expected){checks++;}
            check(pair.dataBytes.get()==0,"Changed source sent data");
        }
    }
    private static void canceledRestart(Path root)throws Exception {
        Path directory=root.resolve("cancel-restart");Files.createDirectories(directory);Path file=directory.resolve("source.bin");Files.write(file,new byte[2*TransferLimits.BLOCK_BYTES]);String id,generation;
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,AttachmentV2Tests.CONNECTION)) {
            pair.holdCredit=true;FileAttachmentSource input=new FileAttachmentSource(file);generation=input.generation();id=pair.a.offer(input,"data.bin","application/octet-stream").get();
            AttachmentV2Tests.waitFor(pair.aRecords,"transferring");pair.a.cancel(id,true);AttachmentV2Tests.waitFor(pair.aRecords,"canceled");AttachmentV2Tests.waitFor(pair.bRecords,"canceled");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(directory,false,"d".repeat(64))) {
            try{pair.a.resume(sender(id,generation),new FileAttachmentSource(file,generation)).get(3,java.util.concurrent.TimeUnit.SECONDS);throw new AssertionError("Canceled task resumed");}catch(java.util.concurrent.ExecutionException expected){checks++;}
        }
    }
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected IOException");}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
}
