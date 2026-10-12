package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Regressions found in the final review, with durable checkpoints and history validation. */
public final class AttachmentBatchReviewTests {
    public static void main(String[] args)throws Exception {
        String filter=args.length==0?"all":args[0];
        if(filter.equals("all")||filter.equals("cancel")){cancelRecovered(false);cancelRecovered(true);cancelUnOfferedSnapshot();}
        if(filter.equals("all")||filter.equals("prepare")){pausePreparation();restartQueuedHistory();}
        if(filter.equals("all")||filter.equals("shutdown"))shutdownDrainsQueueWriter();
        System.out.println("AttachmentBatchReviewTests: recovered cancellation, snapshot disposal and preparation/restart history");
    }
    private static void cancelRecovered(boolean all)throws Exception {
        Path root=Files.createTempDirectory("batch-cancel-recovered-");String id;
        try {
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION);ClientAttachmentTransfers sender=new ClientAttachmentTransfers(root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
                Path file=root.resolve("one");Files.write(file,new byte[]{1});pair.dropComplete=true;
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");id=sender.batchItems().get(0).info().id;sender.shutdown().get(3,TimeUnit.SECONDS);
            }
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,"d".repeat(64));ClientAttachmentTransfers sender=new ClientAttachmentTransfers(root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
                var finished=new java.util.concurrent.atomic.AtomicInteger();sender.batchListener(new AttachmentBatchQueue.Listener(){public void changed(AttachmentRecord record){}public void finished(AttachmentRecord record){finished.incrementAndGet();}});
                (all?sender.cancelBatch():sender.cancel(id,true)).get(3,TimeUnit.SECONDS);
                TransferCheckpoint checkpoint=new TransferCheckpointStore(root.resolve("a/.tasks-v2")).list().stream().filter(c->c.info().id.equals(id)).findFirst().orElseThrow();
                check(checkpoint.state()==TransferCheckpoint.State.CANCELED,"Recovered "+(all?"cancel-all":"cancel")+" left resumable checkpoint: "+checkpoint.state());
                check(sender.batchItems().get(0).state().equals("canceled"),"Recovered batch remained runnable");
                check(finished.get()==1,"Cancellation did not release platform source ownership after checkpoint cancellation");
            }
        }finally{remove(root);}
    }
    private static void cancelUnOfferedSnapshot()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
            sender.pauseBatch().get(3,TimeUnit.SECONDS);Path directory=pair.root.resolve("a/.sources-v2");Files.createDirectories(directory);Path file=directory.resolve(UUID.randomUUID()+".source");Files.write(file,new byte[]{3});
            AttachmentBatchQueue.Selection selected;try(OwnedSnapshotSource source=new OwnedSnapshotSource(new FileAttachmentSource(file),directory)){selected=AttachmentBatchQueue.describe(source,"photo.png","image/png");}
            sender.enqueue(List.of(selected)).get(3,TimeUnit.SECONDS);sender.cancelBatch().get(3,TimeUnit.SECONDS);
            check(!Files.exists(file),"Cancel retained an owned pre-offer source snapshot");
        }
    }
    private static void pausePreparation()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("one");Files.write(file,new byte[]{1});CountDownLatch opening=new CountDownLatch(1),release=new CountDownLatch(1);Map<String,AttachmentRecord> history=new ConcurrentHashMap<>();
            try(ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver(),selected->{opening.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Source gate timed out");}catch(InterruptedException error){throw new IOException(error);}return new FileAttachmentSource(file,selected.generation());})){
                sender.batchListener(record->save(history,record));sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);check(opening.await(3,TimeUnit.SECONDS),"Source never entered preparation");
                CompletableFuture<Void> paused=sender.pauseBatch();release.countDown();paused.get(3,TimeUnit.SECONDS);check(sender.batchItems().get(0).state().equals("paused"),"Preparation did not pause");
                sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                check(!sender.batchItems().get(0).state().equals("failed"),"Resume failed after preparation pause");
            }finally{release.countDown();}
        }
    }
    private static void restartQueuedHistory()throws Exception {
        Path root=Files.createTempDirectory("batch-history-restart-");Map<String,AttachmentRecord> history=new ConcurrentHashMap<>();
        try{
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION);ClientAttachmentTransfers sender=new ClientAttachmentTransfers(root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
                sender.batchListener(record->save(history,record));sender.pauseBatch().get(3,TimeUnit.SECONDS);Path file=root.resolve("one");Files.write(file,new byte[]{9});sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);sender.shutdown().get(3,TimeUnit.SECONDS);
            }
            for(var entry:history.entrySet())history.put(entry.getKey(),entry.getValue().recovered());
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,"e".repeat(64));ClientAttachmentTransfers sender=new ClientAttachmentTransfers(root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
                sender.batchListener(record->save(history,record));sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
            }
        }finally{remove(root);}
    }
    @SuppressWarnings("unchecked")
    private static void shutdownDrainsQueueWriter()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){
            Path file=pair.root.resolve("one");Files.write(file,new byte[]{1});pair.dropComplete=true;sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");
            var batchField=ClientAttachmentTransfers.class.getDeclaredField("batch");batchField.setAccessible(true);Object queue=batchField.get(sender);var actorField=AttachmentBatchQueue.class.getDeclaredField("actor");actorField.setAccessible(true);ThreadPoolExecutor actor=(ThreadPoolExecutor)actorField.get(queue);
            var observerField=AttachmentTransferV2.class.getDeclaredField("taskObserver");observerField.setAccessible(true);java.util.function.Consumer<AttachmentRecord> observer=(java.util.function.Consumer<AttachmentRecord>)observerField.get(pair.a);
            CountDownLatch queued=new CountDownLatch(1),release=new CountDownLatch(1);
            pair.a.taskObserver(record->{if(record.state.equals("paused"))actor.execute(()->{queued.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});observer.accept(record);});
            CompletableFuture<Void> stopping=sender.shutdown();
            try{check(queued.await(3,TimeUnit.SECONDS),"No final queue writer at engine shutdown");Thread.sleep(100);check(!stopping.isDone(),"Shutdown completed while the batch actor still owned pending writes");}
            finally{release.countDown();stopping.get(3,TimeUnit.SECONDS);}
        }
    }
    private static void save(Map<String,AttachmentRecord> history,AttachmentRecord record)throws IOException{AttachmentRecord previous=history.get(record.info.id);if(previous!=null&&!previous.mayReplace(record))throw new IOException("Rejected production history transition: "+previous.state+" -> "+record.state);history.put(record.info.id,record);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void remove(Path root)throws IOException{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
