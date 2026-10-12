package dev.ghost.nearbyim.core;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real pipeline and durable journals, not a mock of the queue's scheduling. */
public final class AttachmentBatchTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()) {
            Path first=pair.root.resolve("one.bin"),second=pair.root.resolve("two.bin");
            Files.write(first,new byte[2*TransferLimits.BLOCK_BYTES+3]);Files.write(second,new byte[]{8,9,10});
            pair.dropComplete=true;
            try(ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
                List<AttachmentBatchQueue.Selection> selected=List.of(AttachmentBatchQueue.selectFile(first),AttachmentBatchQueue.selectFile(second));
                sender.enqueue(selected).get(3,TimeUnit.SECONDS);
                AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");
                Thread.sleep(150);
                check(sender.batchItems().size()==2,"Multi-selection did not create two persistent rows");
                check(sender.batchItems().get(1).state().equals("queued"),"Second file advanced before the first saved receipt");
                check(pair.completeCount.get()==1,"Batch sent the second file before authentic completion");
                sender.pauseAll().get(3,TimeUnit.SECONDS);
                check(sender.batchItems().get(1).state().equals("queued"),"Pausing the batch started its next file");
                pair.dropComplete=false;
                sender.resumeBatch().get(3,TimeUnit.SECONDS);
                AttachmentRecord delivered=AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                check(delivered.info.id.equals(received.info.id),"Resume replaced the first protocol task");
                AttachmentRecord receivedSecond=waitForId(pair.bRecords,sender.batchItems().get(1).info().id);
                AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(pair.root.resolve("b"),receivedSecond.info)),new byte[]{8,9,10}),"Second file content differs");
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(sender.batchItems().stream().anyMatch(item->!item.state().equals("delivered"))&&System.nanoTime()<until)Thread.sleep(10);
                check(sender.batchItems().stream().allMatch(item->item.state().equals("delivered")),"Batch rows did not receive terminal delivery states");
                check(Files.exists(first)&&Files.exists(second),"Batch deleted user originals");
                AttachmentTransfer.clean(pair.root.resolve("a"),Set.of(delivered.info.id));
            }
        }
        Path restored=Files.createTempDirectory("batch-restart-");
        try {
            String secondId;
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(restored,false,AttachmentV2Tests.CONNECTION);
                ClientAttachmentTransfers sender=new ClientAttachmentTransfers(restored.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
                Path one=restored.resolve("first"),two=restored.resolve("second");Files.write(one,new byte[1]);Files.write(two,new byte[]{42});
                pair.dropComplete=true;sender.enqueue(List.of(AttachmentBatchQueue.selectFile(one),AttachmentBatchQueue.selectFile(two))).get(3,TimeUnit.SECONDS);
                AttachmentV2Tests.waitFor(pair.bRecords,"received");secondId=sender.batchItems().get(1).info().id;
                sender.shutdown().get(3,TimeUnit.SECONDS);
                AttachmentTransfer.clean(restored.resolve("a"),Set.of());
            }
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(restored,false,"d".repeat(64));
                ClientAttachmentTransfers sender=new ClientAttachmentTransfers(restored.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
                check(sender.batchItems().size()==2,"Restart discarded queued metadata");
                Thread.sleep(100);check(pair.completeCount.get()==0,"Reconnection silently sent old queued items");
                sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentRecord second=waitForId(pair.bRecords,secondId);
                check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(restored.resolve("b"),second.info)),new byte[]{42}),"Restart failed to deliver the preserved second item");
                AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                sender.shutdown().get(3,TimeUnit.SECONDS);
                AttachmentTransferV2.cancelPending(restored.resolve("a"));
            }
        }finally{try(var paths=Files.walk(restored)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
            Path one=pair.root.resolve("cancel-one"),two=pair.root.resolve("cancel-two");Files.write(one,new byte[1]);Files.write(two,new byte[1]);pair.dropComplete=true;
            sender.enqueue(List.of(AttachmentBatchQueue.selectFile(one),AttachmentBatchQueue.selectFile(two))).get(3,TimeUnit.SECONDS);
            AttachmentV2Tests.waitFor(pair.bRecords,"received");String queued=sender.batchItems().get(1).info().id;
            sender.cancel(queued,true).get(3,TimeUnit.SECONDS);check(sender.batchItems().get(1).state().equals("canceled"),"Canceling a queued item left it runnable");
            sender.shutdown().get(3,TimeUnit.SECONDS);AttachmentTransferV2.cancelPending(pair.root.resolve("a"));
            check(AttachmentBatchQueue.pendingReferences(pair.root.resolve("a")).isEmpty(),"Revocation retained runnable queued sources");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
            Path one=pair.root.resolve("paused-cancel-one"),two=pair.root.resolve("paused-cancel-two");Files.write(one,new byte[1]);Files.write(two,new byte[1]);pair.dropComplete=true;
            sender.enqueue(List.of(AttachmentBatchQueue.selectFile(one),AttachmentBatchQueue.selectFile(two))).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");
            sender.pauseAll().get(3,TimeUnit.SECONDS);sender.cancelAll().get(3,TimeUnit.SECONDS);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(sender.hasActive()&&System.nanoTime()<end)Thread.sleep(10);
            check(!sender.hasActive(),"Canceling a paused batch left an active queue row");check(sender.batchItems().stream().allMatch(item->item.state().equals("canceled")),"Cancel-all did not update paused and queued records");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
            sender.pauseAll().get(3,TimeUnit.SECONDS);Path missing=pair.root.resolve("later-missing"),valid=pair.root.resolve("later-valid");Files.write(missing,new byte[]{1});Files.write(valid,new byte[]{99});
            sender.enqueue(List.of(AttachmentBatchQueue.selectFile(missing),AttachmentBatchQueue.selectFile(valid))).get(3,TimeUnit.SECONDS);Files.delete(missing);String good=sender.batchItems().get(1).info().id;sender.resumeBatch().get(3,TimeUnit.SECONDS);waitForId(pair.bRecords,good);
            check(sender.batchItems().get(0).state().equals("failed"),"Unavailable source blocked the remaining batch");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
            sender.pauseAll().get(3,TimeUnit.SECONDS);List<AttachmentBatchQueue.Selection> huge=new ArrayList<>();for(int i=0;i<100;i++)huge.add(new AttachmentBatchQueue.Selection("content://test/"+"中".repeat(8000),UUID.randomUUID().toString(),"photo.png","image/png",1,true));
            boolean refused=false;try{sender.enqueue(huge).get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){refused=true;}check(refused&&sender.batchItems().isEmpty(),"Oversized journal admission left phantom queued rows");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair();ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())) {
            Path outward=pair.root.resolve("outward"),inward=pair.root.resolve("inward");Files.write(outward,new byte[]{1});Files.write(inward,new byte[]{2});pair.holdReverseData=true;
            String incoming=pair.b.offer(new FileAttachmentSource(inward),"incoming.bin","application/octet-stream").get(3,TimeUnit.SECONDS);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(pair.reverseHeld.get()==0&&System.nanoTime()<until)Thread.sleep(10);check(pair.reverseHeld.get()>0,"Incoming data did not reach the controlled wire gate");pair.dropComplete=true;
            sender.enqueue(List.of(AttachmentBatchQueue.selectFile(outward))).get(3,TimeUnit.SECONDS);long expectedReceive=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(pair.bRecords.stream().noneMatch(r->r.state.equals("received"))&&System.nanoTime()<expectedReceive)Thread.sleep(10);if(pair.bRecords.stream().noneMatch(r->r.state.equals("received")))throw new AssertionError("Duplex setup A="+pair.aRecords.stream().map(r->r.outgoing+":"+r.info.name+":"+r.state).toList()+" B="+pair.bRecords.stream().map(r->r.outgoing+":"+r.info.name+":"+r.state).toList()+" queue="+sender.batchItems().stream().map(AttachmentBatchQueue.Item::state).toList());AttachmentV2Tests.waitFor(pair.bRecords,"received");String active=sender.batchItems().get(0).info().id;sender.pause(active,true).get(3,TimeUnit.SECONDS);
            TransferCheckpoint receiving=new TransferCheckpointStore(pair.root.resolve("a/.tasks-v2")).list().stream().filter(checkpoint->checkpoint.info().id.equals(incoming)).findFirst().orElseThrow();check(receiving.state()==TransferCheckpoint.State.ACTIVE,"Pausing the send queue paused the independent receive direction: "+receiving.state());
        }
        if(!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")){
            Path root=Files.createTempDirectory("batch-platform-alias-");Path real=root.resolve("real"),alias=root.resolve("alias");Files.createDirectories(real);Files.createSymbolicLink(alias,real);
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(alias,false,AttachmentV2Tests.CONNECTION);ClientAttachmentTransfers sender=new ClientAttachmentTransfers(alias.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver())){check(sender.batchItems().isEmpty(),"OS data-directory alias could not initialize a batch");}
            finally{Files.delete(alias);try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        }
        System.out.println("AttachmentBatchTests: real two-file pipeline, saved receipt fence, batch pause/resume, original bytes");
    }
    private static AttachmentRecord waitForId(BlockingQueue<AttachmentRecord> records,String id)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){AttachmentRecord value=records.poll(100,TimeUnit.MILLISECONDS);if(value!=null&&value.state.equals("received")&&value.info.id.equals(id))return value;}
        throw new AssertionError("No completed receive record for "+id);
    }
}
