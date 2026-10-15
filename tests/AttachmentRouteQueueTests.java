package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

/** Real transfer engine, with only network readiness held at its asynchronous boundary. */
public final class AttachmentRouteQueueTests {
    static Method enqueue;
    public static void main(String[] args)throws Exception {
        try{enqueue=ClientAttachmentTransfers.class.getMethod("enqueue",List.class,CompletableFuture.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Files cannot be queued visibly before their route is ready",missing);}
        for(String action:List.of("send","pause","cancel","failure","close"))run(action);
        System.out.println("Route queue: visible admission, no early send, pause/cancel/failure/close passed");
    }
    @SuppressWarnings("unchecked")
    static void run(String action)throws Exception {
        try(var p=new AttachmentV2Tests.Pair();var sender=new ClientAttachmentTransfers(p.root.resolve("a"),p.a,AttachmentBatchQueue.fileResolver())){
            Path file=p.root.resolve("queued.bin");Files.write(file,new byte[]{1,2,3});
            CompletableFuture<Void> route=new CompletableFuture<>();
            ((CompletableFuture<String>)enqueue.invoke(sender,List.of(AttachmentBatchQueue.selectFile(file),AttachmentBatchQueue.selectFile(file)),route)).get(3,TimeUnit.SECONDS);
            check(sender.batchItems().size()==2&&sender.batchItems().stream().allMatch(item->item.state().equals("queued")),"No visible queued item while preparing route");
            Thread.sleep(100);check(p.completeCount.get()==0&&p.bRecords.isEmpty(),"File offered before route authorization");
            check(!((Boolean)ClientAttachmentTransfers.class.getMethod("routeBusy").invoke(sender)),"Waiting admission prevents idle route renegotiation");
            if(action.equals("pause"))sender.pauseBatch().get(3,TimeUnit.SECONDS);
            if(action.equals("cancel"))sender.cancelBatch().get(3,TimeUnit.SECONDS);
            if(action.equals("close"))sender.shutdown().get(3,TimeUnit.SECONDS);
            if(action.equals("failure"))route.completeExceptionally(new IOException("Native route failed"));else route.complete(null);
            if(action.equals("send")){
                for(int i=0;i<2;i++){AttachmentV2Tests.waitFor(p.bRecords,"received");AttachmentV2Tests.waitFor(p.aRecords,"delivered");}
            }else{
                long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                String expected=action.equals("failure")?"failed":action.equals("cancel")?"canceled":action.equals("close")?"queued":"paused";
                while(!sender.batchItems().get(0).state().equals(expected)&&System.nanoTime()<end)Thread.sleep(5);
                check(sender.batchItems().get(0).state().equals(expected),"Route completion lost "+action);
                check(p.completeCount.get()==0,"Route completion restarted "+action+" file");
                if(action.equals("pause")){sender.resumeBatch().get(3,TimeUnit.SECONDS);for(int i=0;i<2;i++){AttachmentV2Tests.waitFor(p.bRecords,"received");AttachmentV2Tests.waitFor(p.aRecords,"delivered");}}
            }
        }
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
