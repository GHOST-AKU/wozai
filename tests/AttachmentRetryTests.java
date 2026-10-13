package dev.ghost.nearbyim.core;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real transfer actors, journals and receipt gates for failed-attempt retries. */
public final class AttachmentRetryTests {
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("shutdown-race")){invalidationDuringSourceOpenRefusesAdmission(true);return;}
        singleRetryIsFreshAndIdempotent();
        batchRetryIsScopedAndPartial();
        explicitReselectionRecoversMissingSource();
        retryLinkSurvivesRestart();
        legacyFailedQueueStillRetries();
        snapshotFailureRetainsOriginalForRetry();
        failedRetryBatchCoalescesLineage();
        pausedRetryDoesNotRestartOthers();
        invalidationDuringSourceOpenRefusesAdmission(false);
        invalidationDuringSourceOpenRefusesAdmission(true);
        System.out.println("AttachmentRetryTests: 10 real transfer/journal scenarios passed (retry identity, dedupe, source recovery, lifecycle guards)");
    }
    private static CompletableFuture<?> invoke(ClientAttachmentTransfers sender,String method,Class<?>[] types,Object... args)throws Exception {
        try{return (CompletableFuture<?>)ClientAttachmentTransfers.class.getMethod(method,types).invoke(sender,args);}
        catch(NoSuchMethodException missing){throw new AssertionError("Failed files need production "+method+" support",missing);}
        catch(InvocationTargetException failure){throw new AssertionError("Retry failed synchronously",failure.getCause());}
    }
    private static CompletableFuture<?> retry(ClientAttachmentTransfers sender,String id)throws Exception{return invoke(sender,"retry",new Class<?>[]{String.class},id);}
    private static Object retryBatch(ClientAttachmentTransfers sender,String id)throws Exception{return invoke(sender,"retryFailedBatch",new Class<?>[]{String.class},id).get(3,TimeUnit.SECONDS);}
    @SuppressWarnings("unchecked") private static List<String> ids(Object result,String accessor)throws Exception{return (List<String>)result.getClass().getMethod(accessor).invoke(result);}
    private static String retryState(ClientAttachmentTransfers sender,String id)throws Exception {
        try{return ClientAttachmentTransfers.class.getMethod("retryState",String.class).invoke(sender,id).toString();}
        catch(NoSuchMethodException missing){throw new AssertionError("Failed cards need production retryState",missing);}
    }
    private static ClientAttachmentTransfers sender(AttachmentV2Tests.Pair pair,Set<String> unavailable)throws IOException {
        return new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver(),selected->{
            if(unavailable.contains(selected.name()))throw new IOException("Fixture source unavailable");
            AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),selected.name(),selected.mime(),Math.max(0,selected.size()),null,0);
            return AttachmentBatchQueue.fileResolver().open(new TransferCheckpoint(pair.a.taskIdentity(info.id,selected.generation()),info,selected.reference(),0,0,0,TransferCheckpoint.State.PAUSED,0));
        });
    }
    private static void singleRetryIsFreshAndIdempotent()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("first.bin");Files.write(file,new byte[]{1,2,3});Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.add("first.bin");
            try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));
                var old=sender.batchItems().get(0);unavailable.clear();pair.dropComplete=true;
                CompletableFuture<?> first=retry(sender,old.info().id),second=retry(sender,old.info().id);
                String fresh=(String)first.get(3,TimeUnit.SECONDS);check(fresh.equals(second.get(3,TimeUnit.SECONDS)),"Double retry created duplicate tasks");
                AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");check(received.info.id.equals(fresh)&&!fresh.equals(old.info().id),"Retry reused failed protocol identity");
                check(sender.batchItems().size()==2&&sender.batchItems().get(0).state().equals("failed"),"Retry rewrote the failed history row");
                var child=sender.batchItems().get(1);check(!child.selection().generation().equals(old.selection().generation()),"Retry reused source generation");
                check(retryState(sender,old.info().id).equals("RETRYING"),"Failed card does not reflect pending retry");
                check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(pair.root.resolve("b"),received.info)),new byte[]{1,2,3}),"Retry changed source bytes");
                sender.pauseBatch().get(3,TimeUnit.SECONDS);pair.dropComplete=false;sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                until(()->retryState(sender,old.info().id).equals("RETRIED"));check(fresh.equals(retry(sender,old.info().id).get(3,TimeUnit.SECONDS)),"Old retry button resent an already delivered retry");
                check(sender.batchItems().size()==2,"Completed retry allocated another task");
            }
        }
    }
    private static void batchRetryIsScopedAndPartial()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.addAll(List.of("a.bin","c.bin","outside.bin"));
            List<Path> paths=new ArrayList<>();for(String name:List.of("a.bin","ok.bin","c.bin","outside.bin")){Path file=pair.root.resolve(name);Files.write(file,new byte[]{(byte)paths.size()});paths.add(file);}
            try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(paths.get(0)),AttachmentBatchQueue.selectFile(paths.get(1)),AttachmentBatchQueue.selectFile(paths.get(2)))).get(3,TimeUnit.SECONDS);
                until(()->sender.batchItems().size()==3&&sender.batchItems().stream().allMatch(AttachmentBatchQueue.Item::terminal));AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(paths.get(3)))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().size()==4&&sender.batchItems().get(3).terminal());
                String a=sender.batchItems().get(0).info().id,c=sender.batchItems().get(2).info().id,outside=sender.batchItems().get(3).info().id;unavailable.remove("a.bin");
                Object result=retryBatch(sender,a);check(ids(result,"queuedIds").size()==1&&ids(result,"unavailableIds").equals(List.of(c)),"Batch retry must queue readable failures and report missing ones");
                AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(received.info.id.equals(ids(result,"queuedIds").get(0)),"Batch queued the wrong retry");
                until(()->retryState(sender,a).equals("RETRIED"));check(sender.batchItems().stream().filter(i->i.info().id.equals(outside)).findFirst().orElseThrow().state().equals("failed"),"Batch retry touched another batch");
                unavailable.remove("c.bin");Object again=retryBatch(sender,a);check(ids(again,"queuedIds").size()==1&&ids(again,"unavailableIds").isEmpty(),"Second batch retry duplicated successful entries");
                AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");until(()->retryState(sender,c).equals("RETRIED"));
                check(sender.batchItems().size()==6,"Scoped retries resent delivered or outside files");
            }
        }
    }
    private static void explicitReselectionRecoversMissingSource()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("gone.bin");Files.write(file,new byte[]{7});Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.add("gone.bin");
            try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));String old=sender.batchItems().get(0).info().id;
                boolean missing=false;try{retry(sender,old).get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){missing=expected.getCause().getClass().getSimpleName().equals("RetrySourceUnavailableException");}
                check(missing&&sender.batchItems().size()==1,"Unreadable source should request reselection without a phantom retry");
                Path replacement=pair.root.resolve("replacement.bin");Files.write(replacement,new byte[]{8,9});
                String newId=(String)invoke(sender,"retryWithSelection",new Class<?>[]{String.class,AttachmentBatchQueue.Selection.class},old,AttachmentBatchQueue.selectFile(replacement)).get(3,TimeUnit.SECONDS);
                AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(received.info.id.equals(newId)&&received.info.name.equals("replacement.bin"),"Explicit reselection lost selected metadata or lineage");
                check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(pair.root.resolve("b"),received.info)),new byte[]{8,9}),"Reselected file bytes changed");
                check(sender.batchItems().get(0).state().equals("failed"),"Reselection erased old failure");
            }
        }
    }
    private static void retryLinkSurvivesRestart()throws Exception {
        Path root=Files.createTempDirectory("retry-restart-");String old,fresh;
        try {
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,AttachmentV2Tests.CONNECTION)){
                Path file=root.resolve("retry.bin");Files.write(file,new byte[]{4,5});Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.add("retry.bin");
                try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                    sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));old=sender.batchItems().get(0).info().id;unavailable.clear();pair.dropComplete=true;
                    fresh=(String)retry(sender,old).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");sender.shutdown().get(3,TimeUnit.SECONDS);
                }
            }
            try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,false,"d".repeat(64));ClientAttachmentTransfers sender=sender(pair,Set.of())){
                check(retryState(sender,old).equals("RETRYING")&&sender.batchItems().size()==2,"Restart lost retry lineage");
                check(fresh.equals(retry(sender,old).get(3,TimeUnit.SECONDS))&&sender.batchItems().size()==2,"Restart double click duplicated a paused retry");
                sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.aRecords,"delivered");until(()->retryState(sender,old).equals("RETRIED"));
            }
        }finally{remove(root);}
    }
    private static void legacyFailedQueueStillRetries()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("legacy.bin");Files.write(file,new byte[]{6});var selection=AttachmentBatchQueue.selectFile(file);String id=UUID.randomUUID().toString();var info=AttachmentInfo.v2(id,"legacy.bin","application/octet-stream",1,null,1);
            TransferTaskKey identity=pair.a.taskIdentity(id,selection.generation());Path journal=pair.root.resolve("a/.batch-v1");PrivateMetadata.directory(journal);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){out.writeInt(0x57425131);out.writeUTF(identity.localRoot());out.writeUTF(identity.remoteRoot());out.writeBoolean(false);out.writeInt(1);out.writeUTF(UUID.randomUUID().toString());info.writeV2(out);out.writeUTF(selection.reference());out.writeUTF(selection.generation());out.writeLong(selection.size());out.writeBoolean(selection.exact());out.writeUTF("failed");out.writeLong(0);}
            PrivateMetadata.write(journal.resolve(PrivateMetadata.key(identity.localRoot()+"\n"+identity.remoteRoot())+".bin"),bytes.toByteArray(),AttachmentBatchStore.LIMIT);
            try(ClientAttachmentTransfers sender=sender(pair,Set.of())){
                String fresh=(String)retry(sender,id).get(3,TimeUnit.SECONDS);AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(received.info.id.equals(fresh)&&!fresh.equals(id),"Old WBQ1 failed history could not retry safely");
            }
        }
    }
    private interface Condition {boolean check()throws Exception;}
    private static void until(Condition condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<deadline){if(condition.check())return;Thread.sleep(10);}throw new AssertionError("Retry did not reach expected durable state");}
    private static void snapshotFailureRetainsOriginalForRetry()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            byte[] content={3,1,4,1,5};String uri="content://retry/original";
            AttachmentBatchQueue.SourceResolver reopen=selection->{
                if(selection.reference().startsWith("snapshot:\n")){
                    AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),selection.name(),selection.mime(),selection.size(),null,0);
                    return AttachmentBatchQueue.fileResolver().open(new TransferCheckpoint(pair.a.taskIdentity(info.id,selection.generation()),info,selection.reference(),0,0,0,TransferCheckpoint.State.PAUSED,0));
                }
                return new AttachmentSource(){public long size(){return content.length;}public String generation(){return selection.generation();}public String persistentReference(){return uri;}public boolean seekable(){return false;}public void verifyUnchanged(){}public InputStream open(long offset){return new ByteArrayInputStream(content);}public void close(){}};
            };
            try(ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver(),reopen)){
                pair.corruptEnd=true;sender.enqueue(List.of(new AttachmentBatchQueue.Selection(uri,UUID.randomUUID().toString(),"pipe.bin","application/octet-stream",content.length,false))).get(3,TimeUnit.SECONDS);
                AttachmentV2Tests.waitFor(pair.aRecords,"failed");until(()->sender.batchItems().get(0).state().equals("failed"));var old=sender.batchItems().get(0);
                check(old.selection().reference().startsWith("snapshot:\n")&&old.origin().reference().equals(uri),"Prepared snapshot erased original provider selection");
                check(AttachmentBatchQueue.pendingReferences(pair.root.resolve("a")).equals(List.of(uri)),"Failed lineage must retain only its original URI for grant cleanup");
                pair.corruptEnd=false;String fresh=(String)retry(sender,old.info().id).get(3,TimeUnit.SECONDS);AttachmentRecord received=AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");
                until(()->retryState(sender,old.info().id).equals("RETRIED"));check(received.info.id.equals(fresh)&&Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(pair.root.resolve("b"),received.info)),content),"Retry attempted the disposed snapshot instead of original provider bytes");
                check(AttachmentBatchQueue.pendingReferences(pair.root.resolve("a")).isEmpty(),"Successful retry retained original source permission indefinitely");
            }
        }
    }
    private static void failedRetryBatchCoalescesLineage()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("lineage.bin");Files.write(file,new byte[]{9});Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.add("lineage.bin");
            try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));String root=sender.batchItems().get(0).info().id;unavailable.clear();pair.corruptEnd=true;
                String child=(String)retry(sender,root).get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.aRecords,"failed");until(()->sender.batchItems().size()==2&&sender.batchItems().get(1).state().equals("failed"));pair.corruptEnd=false;
                Object batch=retryBatch(sender,root);check(ids(batch,"queuedIds").size()==1,"Batch retried both failed ancestor and failed child");String latest=ids(batch,"queuedIds").get(0);AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");until(()->retryState(sender,root).equals("RETRIED"));
                check(sender.batchItems().size()==3&&latest.equals(retry(sender,child).get(3,TimeUnit.SECONDS)),"Old child did not resolve to latest successful attempt");
            }
        }
    }
    private static void pausedRetryDoesNotRestartOthers()throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("paused.bin");Files.write(file,new byte[]{1});Set<String> unavailable=ConcurrentHashMap.newKeySet();unavailable.add("paused.bin");
            try(ClientAttachmentTransfers sender=sender(pair,unavailable)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));String failed=sender.batchItems().get(0).info().id;unavailable.clear();sender.pauseBatch().get(3,TimeUnit.SECONDS);
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);String child=(String)retry(sender,failed).get(3,TimeUnit.SECONDS);
                check(sender.batchItems().get(2).state().equals("paused")&&pair.completeCount.get()==0,"Retry restarted a globally paused send queue");
                sender.cancel(child,true).get(3,TimeUnit.SECONDS);check(retryState(sender,failed).equals("INELIGIBLE"),"Canceled lineage was confused with a missing source");check(!sender.hasRetryableBatch(failed),"Canceled retry left its failed ancestor eligible for batch resend");
                boolean refused=false;try{retry(sender,failed).get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){refused=true;}check(refused&&sender.batchItems().size()==3,"Canceled retry was silently restarted");
                sender.resumeBatch().get(3,TimeUnit.SECONDS);AttachmentV2Tests.waitFor(pair.bRecords,"received");AttachmentV2Tests.waitFor(pair.aRecords,"delivered");check(sender.batchItems().get(2).state().equals("canceled"),"Continue revived a canceled retry");
            }
        }
    }
    private static void invalidationDuringSourceOpenRefusesAdmission(boolean shutdown)throws Exception {
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            Path file=pair.root.resolve("guard.bin");Files.write(file,new byte[]{2});java.util.concurrent.atomic.AtomicBoolean missing=new java.util.concurrent.atomic.AtomicBoolean(true),allowed=new java.util.concurrent.atomic.AtomicBoolean(true);
            CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);
            AttachmentBatchQueue.SourceResolver resolver=selection->{if(missing.get())throw new IOException("Fixture unavailable");opened.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Probe gate timeout");}catch(InterruptedException error){throw new IOException(error);}return new FileAttachmentSource(file,selection.generation());};
            try(ClientAttachmentTransfers sender=new ClientAttachmentTransfers(pair.root.resolve("a"),pair.a,AttachmentBatchQueue.fileResolver(),resolver)){
                sender.enqueue(List.of(AttachmentBatchQueue.selectFile(file))).get(3,TimeUnit.SECONDS);until(()->sender.batchItems().get(0).state().equals("failed"));String id=sender.batchItems().get(0).info().id;missing.set(false);
                CompletableFuture<?> retry=shutdown?retry(sender,id):invoke(sender,"retry",new Class<?>[]{String.class,java.util.function.BooleanSupplier.class},id,(java.util.function.BooleanSupplier)allowed::get);
                check(opened.await(2,TimeUnit.SECONDS),"Retry did not enter controlled source-open gate");CompletableFuture<Void> stopped=null;if(shutdown)stopped=sender.shutdown();else allowed.set(false);release.countDown();
                boolean rejected=false;try{retry.get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){rejected=true;}
                check(rejected&&sender.batchItems().size()==1&&pair.completeCount.get()==0,"Revoked retry admitted a child after source-open race");if(stopped!=null)stopped.get(3,TimeUnit.SECONDS);
            }finally{release.countDown();}
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void remove(Path root)throws IOException{try(var files=Files.walk(root)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
}
