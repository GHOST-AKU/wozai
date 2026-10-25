package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A real receiver history save briefly rejects the first authenticated quality request. */
public final class FileQualityRequestRetryTests {
    public static void main(String[] args)throws Exception {
        transientBusy();
        unansweredBudget();
        for(String action:new String[]{"pause","cancel","close"})stoppedRequest(action);
        System.out.println("Quality request retry: busy recovery, bounded unanswered budget, pause/cancel/close, unchanged bytes and one saved receipt passed");
    }
    static void transientBusy()throws Exception {
        try(var h=new AutomaticFileHandoverTests.Harness("lan",1,false)){
            AtomicBoolean holdHistory=new AtomicBoolean();AtomicInteger requests=new AtomicInteger();
            CountDownLatch historyEntered=new CountDownLatch(1),historyRelease=new CountDownLatch(1),declined=new CountDownLatch(1),retried=new CountDownLatch(1);
            CompletableFuture<Void> controlFailure=new CompletableFuture<>();
            ExecutorService admission=Executors.newSingleThreadExecutor();
            h.historyBarrier=record->{
                if(!record.outgoing&&record.state.equals("transferring")&&holdHistory.compareAndSet(true,false)){
                    historyEntered.countDown();
                    try{if(!historyRelease.await(5,TimeUnit.SECONDS))throw new IOException("History barrier was not released");}
                    catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}
                }
            };
            h.routes.a.linkConsumer=packet->{
                try{
                    if(packet.kind==FileLinkPacket.Kind.REQUEST&&packet.number==2){
                        int count=requests.incrementAndGet();
                        if(count==1){
                            holdHistory.set(true);
                            // Keep the parent reader free to receive authenticated activity heartbeats.
                            admission.execute(()->{
                                try{
                                    check(historyEntered.await(5,TimeUnit.SECONDS),"Receiver did not enter its real history save");
                                    check(h.files[0].activity().busy,"History save did not make receiver busy");
                                    h.routes.a.files.receiveFileLink(packet);
                                    AutomaticFileRoutesTests.drainActor(h.routes.a.files);
                                    String diagnostics=h.routes.a.files.diagnostics();
                                    check(diagnostics.contains("file_quality_result=not_measured"),"First request was not declined before measurement: "+diagnostics);
                                    check(diagnostics.contains("file_quality_admission=local_busy"),"Receiver did not explain its temporary rejection: "+diagnostics);
                                    declined.countDown();
                                }catch(Exception|AssertionError error){controlFailure.completeExceptionally(error);}
                                finally{historyRelease.countDown();}
                            });
                            return;
                        }
                        // Preserve first-admission ordering while unrelated control records still pass.
                        if(declined.getCount()!=0)return;
                        retried.countDown();
                    }
                    h.routes.a.files.receiveFileLink(packet);
                }catch(Exception|AssertionError error){controlFailure.completeExceptionally(error);historyRelease.countDown();}
            };
            CompletableFuture<Void> sending=CompletableFuture.runAsync(()->{try{h.transfer();}catch(Exception error){throw new CompletionException(error);}});
            try{
                check(declined.await(8,TimeUnit.SECONDS),"First-request barrier not reached: "+controlFailure);
                check(retried.await(4,TimeUnit.SECONDS),"A transiently declined quality request suppressed comparison for the whole file");
                sending.get(45,TimeUnit.SECONDS);
                check(!h.routes.a.files.diagnostics().contains("file_quality_result=not_measured"),"Retried request never started a comparison");
                check(h.routes.a.wire.isReady()&&h.routes.b.wire.isReady(),"Quality retry disconnected the parent chat");
                check(h.routes.a.errors.isEmpty()&&h.routes.b.errors.isEmpty(),"Quality retry exposed a terminal failure");
                check(requests.get()>=2&&requests.get()<=3,"Quality retry exceeded its bounded request count");
                check(h.routes.b.files.diagnostics().contains("file_quality_request_state=accepted"),"PREPARE did not retire the pending request");
                if(controlFailure.isCompletedExceptionally())controlFailure.join();
            }finally{
                historyRelease.countDown();h.saved.completeExceptionally(new CancellationException());h.delivered.completeExceptionally(new CancellationException());
                admission.shutdownNow();check(admission.awaitTermination(3,TimeUnit.SECONDS),"Admission fixture worker did not stop");
            }
        }
    }
    static void unansweredBudget()throws Exception {
        try(var h=new AutomaticFileHandoverTests.Harness("lan",1,false)){
            AtomicInteger requests=new AtomicInteger();
            h.routes.a.linkConsumer=packet->{
                if(packet.kind==FileLinkPacket.Kind.REQUEST&&packet.number==2)requests.incrementAndGet();
                else h.routes.a.files.receiveFileLink(packet);
            };
            h.transfer();
            check(requests.get()==3,"Unanswered quality requests were not bounded to three: "+requests);
            check(h.routes.b.files.diagnostics().contains("file_quality_request_state=unanswered"),"Exhausted request budget was not explained");
            check(h.routes.a.files.route().equals("wifi-direct"),"An unanswered comparison displaced a working direct lane");
        }
    }
    static void stoppedRequest(String action)throws Exception {
        try(var h=new AutomaticFileHandoverTests.Harness("lan",1,false)){
            CountDownLatch first=new CountDownLatch(1),extra=new CountDownLatch(1);AtomicInteger requests=new AtomicInteger();
            h.routes.a.linkConsumer=packet->{
                if(packet.kind==FileLinkPacket.Kind.REQUEST&&packet.number==2){if(requests.incrementAndGet()==1)first.countDown();else extra.countDown();}
                else h.routes.a.files.receiveFileLink(packet);
            };
            h.routes.a.files.ensureReady().get(5,TimeUnit.SECONDS);h.routes.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            h.files[1].enqueue(java.util.List.of(AttachmentBatchQueue.selectFile(h.source))).get(3,TimeUnit.SECONDS);
            check(first.await(8,TimeUnit.SECONDS),"No initial quality request before "+action);
            switch(action){
                case "pause":h.files[1].pauseBatch().get(3,TimeUnit.SECONDS);break;
                case "cancel":h.files[1].cancelBatch().get(3,TimeUnit.SECONDS);break;
                case "close":h.routes.b.files.close();break;
                default:throw new AssertionError(action);
            }
            check(!extra.await(2,TimeUnit.SECONDS),"Quality requests continued after "+action);
            check(h.savedCount.get()==0,"Quality retry resumed a stopped task after "+action);
        }
    }
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
