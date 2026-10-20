package dev.ghost.nearbyim.core;

import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Saved session shutdown must not turn a later explicit send into an invisible pause. */
public final class AttachmentQueueSessionTests {
    public static void main(String[] args)throws Exception {
        String which=args.length==0?"all":args[0];
        if(which.equals("all")||which.equals("empty"))sendAfterIdleReconnect();
        if(which.equals("all")||which.equals("complete"))sendAfterCompletedReconnect();
        if(which.equals("all")||which.equals("recover"))freshSendDoesNotResumeRecoveredQueue();
        if(which.equals("all")||which.equals("pause"))pausedQueueExposesContinue();
        if(which.equals("all")||which.equals("active"))pauseStopsFollowingFiles();
        if(which.equals("all")||which.equals("unavailable"))temporarilyUnavailableSourceContinues();
        System.out.println("AttachmentQueueSessionTests: 6 reconnect/pause/provider-recovery scenarios passed");
    }
    private static ClientAttachmentTransfers sender(AttachmentV2Tests.Pair pair)throws Exception{return new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver());}
    private static void sendAfterIdleReconnect()throws Exception {
        Path root=Files.createTempDirectory("idle-queue-reconnect-");
        try {
            try(var pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION);var sender=sender(pair)){sender.shutdown().get(3,TimeUnit.SECONDS);}
            try(var pair=new AttachmentV2Tests.Pair(root,false,"d".repeat(64));var sender=sender(pair)){
                Path file=root.resolve("one.bin");Files.write(file,new byte[]{1,2});sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);
                received(pair,sender,1);check(sender.batchItems().get(0).state().equals("delivered"),"Fresh send stayed queued after idle reconnect");
            }
        }finally{remove(root);}
    }
    private static void sendAfterCompletedReconnect()throws Exception {
        Path root=Files.createTempDirectory("complete-queue-reconnect-");
        try {
            Path file=root.resolve("old.bin");Files.write(file,new byte[]{3});
            try(var pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION);var sender=sender(pair)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);received(pair,sender,1);sender.shutdown().get(3,TimeUnit.SECONDS);
            }
            try(var pair=new AttachmentV2Tests.Pair(root,false,"e".repeat(64));var sender=sender(pair)){
                Path one=root.resolve("new-one.bin"),two=root.resolve("new-two.bin");Files.write(one,new byte[]{4});Files.write(two,new byte[]{5});sender.enqueue(List.of(AttachmentBatchQueue.selectFile(one),AttachmentBatchQueue.selectFile(two))).get(3,TimeUnit.SECONDS);
                received(pair,sender,2);check(sender.batchItems().stream().allMatch(i->i.state().equals("delivered")),"New batch stayed queued behind a completed session's pause flag");
            }
        }finally{remove(root);}
    }
    private static void freshSendDoesNotResumeRecoveredQueue()throws Exception {
        Path root=Files.createTempDirectory("pending-queue-reconnect-");
        try {
            Path old=root.resolve("old.bin"),fresh=root.resolve("fresh.bin");Files.write(old,new byte[]{6});Files.write(fresh,new byte[]{7});String oldId;
            try(var pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION);var sender=sender(pair)){
                sender.pauseBatch().get(3,TimeUnit.SECONDS);sender.enqueue(List.of(AttachmentBatchQueue.selectFile(old))).get(3,TimeUnit.SECONDS);oldId=sender.batchItems().get(0).info().id;sender.shutdown().get(3,TimeUnit.SECONDS);
            }
            try(var pair=new AttachmentV2Tests.Pair(root,false,"f".repeat(64));var sender=sender(pair)){
                check(sender.batchItems().get(0).record().resumable(),"Recovered unoffered file hides Continue behind a queued state");
                check(!sender.routeBusy(),"Recovered paused history blocks idle P2P negotiation");
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(fresh))).get(3,TimeUnit.SECONDS);AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                check(!received.info.id.equals(oldId)&&received.info.name.equals("fresh.bin"),"Explicit fresh send restarted an older paused file");until(()->sender.batchItems().get(1).state().equals("delivered"));check(sender.batchItems().get(0).state().equals("paused"),"Recovered file lost its intentional pause");
                sender.resumeBatch().get(3,TimeUnit.SECONDS);received(pair,sender,1);check(pair.completeCount.get()==2,"Recovered queue Continue duplicated a delivered fresh send");
            }
        }finally{remove(root);}
    }
    private static void pausedQueueExposesContinue()throws Exception {
        try(var pair=new AttachmentV2Tests.Pair();var sender=sender(pair)){
            sender.pauseBatch().get(3,TimeUnit.SECONDS);Path file=pair.root.resolve("one.bin");Files.write(file,new byte[]{8});sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);
            check(sender.batchItems().get(0).record().resumable(),"Explicitly paused enqueue still shows Pause instead of Continue");check(pair.completeCount.get()==0,"New selection ignored an explicit pause in the current session");sender.resumeBatch().get(3,TimeUnit.SECONDS);received(pair,sender,1);
        }
    }
    private static void pauseStopsFollowingFiles()throws Exception {
        try(var pair=new AttachmentV2Tests.Pair();var sender=sender(pair)){
            pair.dropComplete=true;Path one=pair.root.resolve("first.bin"),two=pair.root.resolve("second.bin");Files.write(one,new byte[]{9});Files.write(two,new byte[]{10});sender.enqueue(List.of(AttachmentBatchQueue.selectFile(one),AttachmentBatchQueue.selectFile(two))).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");
            check(sender.routeBusy(),"An active transfer awaiting its receipt permits an idle route recheck");
            sender.pauseBatch().get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).record().resumable());check(sender.batchItems().get(1).record().resumable(),"Paused following file still exposes a non-working Pause button");check(pair.completeCount.get()==1,"Paused queue advanced beyond held receipt");
            check(!sender.routeBusy(),"A completed manual pause still occupies the FILE lane");
            pair.dropComplete=false;sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.aRecords,"delivered");received(pair,sender,1);check(pair.completeCount.get()>=2,"Continue did not deliver the following file");
        }
    }
    private static void temporarilyUnavailableSourceContinues()throws Exception {
        try(var pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("temporary.bin");Files.write(file,new byte[]{11,12,13});
            java.util.concurrent.atomic.AtomicBoolean unavailable=new java.util.concurrent.atomic.AtomicBoolean(true);
            try(var sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver(),selection->{
                FileAttachmentSource source=new FileAttachmentSource(file,selection.generation());
                return new AttachmentSource(){
                    public long size(){return source.size();}public String generation(){return source.generation();}public boolean seekable(){return true;}public String persistentReference(){return source.persistentReference();}
                    public InputStream open(long offset)throws IOException{if(unavailable.get())throw new IOException("Temporary provider failure");return source.open(offset);}
                    public void verifyUnchanged()throws IOException{source.verifyUnchanged();}public void close()throws IOException{source.close();}
                };
            })){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);
                AttachmentV2Tests.waitFor(pair.aRecords,"sourceUnavailable");until(()->sender.batchItems().get(0).state().equals("sourceUnavailable"));
                check(sender.batchItems().get(0).record().resumable(),"Unavailable source did not expose Continue");
                check(!sender.routeBusy(),"A dormant unavailable source blocks idle P2P negotiation");
                unavailable.set(false);sender.resumeBatch().get(3,TimeUnit.SECONDS);received(pair,sender,1);
                check(sender.batchItems().get(0).state().equals("delivered"),"Restored source did not continue after a temporary read error");
            }
        }
    }
    private static void received(AttachmentV2Tests.Pair pair,ClientAttachmentTransfers sender,int count)throws Exception {
        for(int i=0;i<count;i++){AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");}
        until(()->sender.batchItems().stream().allMatch(i->i.terminal()||i.state().equals("paused")));
    }
    private interface Check {boolean get()throws Exception;}
    private static void until(Check check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(System.nanoTime()<end){if(check.get())return;Thread.sleep(5);}throw new AssertionError("Queue state did not reach expected condition");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void remove(Path root)throws Exception {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
