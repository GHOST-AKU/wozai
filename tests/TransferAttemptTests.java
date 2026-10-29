package dev.ghost.nearbyim.core;
import java.util.concurrent.atomic.AtomicLong;
public final class TransferAttemptTests {
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        AtomicLong clock=new AtomicLong();
        TransferAttempt attempt=new TransferAttempt("test",true,clock::get,1000);
        attempt.phase("checking");clock.set(1_000_000_000L);attempt.phase("transferring");
        attempt.data(1048576,1048576);attempt.progress(2097152);
        clock.set(2_000_000_000L);
        attempt.hashVerified();attempt.peerCompleteReceived();String summary=attempt.snapshot("delivered",2097152,2097152,2097152,"a".repeat(64),true);
        check(summary.contains("\"average_mib_s\":0.5"),"Restored prefix inflated goodput");
        check(summary.contains("\"resumed_prefix_bytes\":1048576"),"Missing resumed prefix");
        check(summary.contains("\"peer_complete_received\":true"),"Missing saved receipt");
        check(summary.contains("\"checking\":1000.0"),"Missing phase time");
        clock.set(100_000_000_000L);check(summary.equals(attempt.snapshot("delivered",2097152,2097152,2097152,null,true)),"Idle time changed completed summary");
        TransferAttempt failure=new TransferAttempt("test",true,clock::get,2000);failure.data(0,1000);
        clock.addAndGet(1_000_000_000L);String rejected=failure.snapshot("failed",1000,0,0,null,true);
        check(rejected.contains("\"confirmed_payload_bytes\":0")&&rejected.contains("\"average_mib_s\":0.0"),"Queued bytes claimed delivered goodput");
        check(rejected.contains("\"local_hash_verified\":false"),"Failure claimed hash success");
        TransferAttempt savedFailure=new TransferAttempt("saved-failure",true,clock::get,2500);savedFailure.peerCompleteReceived();
        check(savedFailure.snapshot("history_save_failed",0,0,0,null,true).contains("\"peer_complete_received\":true"),"Actual peer COMPLETE lost when local persistence failed");
        TransferAttempt empty=new TransferAttempt("empty",false,clock::get,3000);
        check(!empty.snapshot("received",0,0,0,"b".repeat(64),true).contains("NaN"),"Empty transfer has invalid rate");
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            var file=pair.root.resolve("summary.txt");java.nio.file.Files.writeString(file,"diagnostic test");
            pair.a.offer(new FileAttachmentSource(file),"summary.txt","text/plain").get(3,java.util.concurrent.TimeUnit.SECONDS);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){String value=pair.a.diagnostics();if(value.contains("\"state\":\"delivered\"")&&value.contains("\"terminal\":true"))break;Thread.sleep(10);}
            String sent=pair.a.diagnostics(),received=pair.b.diagnostics();
            check(sent.contains("\"terminal\":true")&&sent.contains("\"attempt_data_bytes\":15"),"Missing completed sender summary");
            check(received.contains("\"local_hash_verified\":true"),"Missing receiver hash proof");
            check(sent.contains("\"sha256\":\"")&&received.contains("\"sha256\":\""),"Missing hash");
        }
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            pair.failSavedHistory=true;var file=pair.root.resolve("save-failure.txt");java.nio.file.Files.writeString(file,"persist failure");
            pair.a.offer(new FileAttachmentSource(file),"save-failure.txt","text/plain").get(3,java.util.concurrent.TimeUnit.SECONDS);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!pair.b.diagnostics().contains("history_save_failed")&&System.nanoTime()<end)Thread.sleep(10);
            String failed=pair.b.diagnostics();
            check(failed.contains("history_save_failed")&&failed.contains("\"local_hash_verified\":true"),"Hash success and history failure must be independent");
            check(pair.completeCount.get()==0,"Failed history save emitted COMPLETE");
            check(!pair.a.diagnostics().contains("\"peer_complete_received\":true"),"Sender claimed a missing saved receipt");
        }
        TransferAttempt paused=new TransferAttempt("paused",true,clock::get,4000);paused.restoredPrefix(1024);
        check(paused.snapshot("paused",2048,1024,1024,null,true).contains("\"resumed_prefix_bytes\":1024"),"Pre-DATA pause lost checkpoint prefix");
        TransferAttempt rollback=new TransferAttempt("rollback",false,clock::get,4000);rollback.restoredPrefix(1024);rollback.restoredPrefix(0);
        check(rollback.snapshot("failed",2048,0,0,null,true).contains("\"resumed_prefix_bytes\":0"),"Verified prefix rollback was ignored");
        resumeWithoutData();
        System.out.println("TransferAttemptTests: real transfer summaries, monotonic duration, restored prefix, failed queue, empty file and idle freeze passed");
    }
    private static void resumeWithoutData()throws Exception {
        var root=java.nio.file.Files.createTempDirectory("attempt-prefix-");
        byte[] bytes=new byte[TransferLimits.BLOCK_BYTES+17];var file=root.resolve("source.bin");java.nio.file.Files.write(file,bytes);
        String id=java.util.UUID.randomUUID().toString(),generation=java.util.UUID.randomUUID().toString();
        var send=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,id,generation);
        var receive=new TransferTaskKey("b".repeat(64),"a".repeat(64),TransferTaskKey.Direction.RECEIVE,id,generation);
        var info=AttachmentInfo.v2(id,"source.bin","application/octet-stream",bytes.length,null,1);
        var original=new FileAttachmentSource(file,generation);long offset=TransferLimits.BLOCK_BYTES;
        var a=new TransferCheckpointStore(root.resolve("a/.tasks-v2"));var b=new TransferCheckpointStore(root.resolve("b/.tasks-v2"));
        a.appendBlock(send,offset,java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(bytes,(int)offset)));
        a.checkpoint(new TransferCheckpoint(send,info,original.persistentReference(),offset,offset,offset,TransferCheckpoint.State.PAUSED,System.currentTimeMillis()));
        java.nio.file.Files.write(root.resolve("b").resolve(receive.fileName()+".part"),java.util.Arrays.copyOf(bytes,(int)offset));
        b.appendBlock(receive,offset,java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOf(bytes,(int)offset)));
        b.checkpoint(new TransferCheckpoint(receive,info,"",offset,offset,offset,TransferCheckpoint.State.PAUSED,System.currentTimeMillis()));
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair(root,true,AttachmentV2Tests.CONNECTION)){
            AttachmentSource unavailable=new AttachmentSource(){
                public long size(){return original.size();}public String generation(){return original.generation();}public boolean seekable(){return true;}
                public String persistentReference(){return original.persistentReference();}public void verifyUnchanged()throws java.io.IOException{original.verifyUnchanged();}
                public java.io.InputStream open(long start)throws java.io.IOException{if(start>0)throw new java.io.IOException("Injected source open failure after prefix negotiation");return original.open(start);}
                public void close()throws java.io.IOException{original.close();}
            };
            pair.a.resume(send,unavailable).get(3,java.util.concurrent.TimeUnit.SECONDS);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<end){String value=pair.a.diagnostics();if(value.contains("\"state\":\"sourceUnavailable\"")&&value.contains("\"terminal\":true"))break;Thread.sleep(10);}
            String report=pair.a.diagnostics();
            check(report.contains("\"terminal\":true")&&report.contains("\"resumed_prefix_bytes\":1048576"),"Pre-DATA source failure lost negotiated resume prefix: "+report);
            check(report.contains("\"attempt_data_bytes\":0")&&report.contains("\"confirmed_payload_bytes\":0")&&pair.dataBytes.get()==0,"Restored prefix counted as new DATA");
            check(java.nio.file.Files.size(file)==bytes.length,"Diagnostic failure handling damaged original source");
        }
    }

}
