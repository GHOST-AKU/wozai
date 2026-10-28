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
        attempt.hashVerified();String summary=attempt.snapshot("delivered",2097152,2097152,2097152,"a".repeat(64),true);
        check(summary.contains("\"average_mib_s\":0.5"),"Restored prefix inflated goodput");
        check(summary.contains("\"resumed_prefix_bytes\":1048576"),"Missing resumed prefix");
        check(summary.contains("\"peer_complete_received\":true"),"Missing saved receipt");
        check(summary.contains("\"checking\":1000.0"),"Missing phase time");
        clock.set(100_000_000_000L);check(summary.equals(attempt.snapshot("delivered",2097152,2097152,2097152,null,true)),"Idle time changed completed summary");
        TransferAttempt failure=new TransferAttempt("test",true,clock::get,2000);failure.data(0,1000);
        clock.addAndGet(1_000_000_000L);String rejected=failure.snapshot("failed",1000,0,0,null,true);
        check(rejected.contains("\"confirmed_payload_bytes\":0")&&rejected.contains("\"average_mib_s\":0.0"),"Queued bytes claimed delivered goodput");
        check(rejected.contains("\"local_hash_verified\":false"),"Failure claimed hash success");
        TransferAttempt empty=new TransferAttempt("empty",false,clock::get,3000);
        check(!empty.snapshot("received",0,0,0,"b".repeat(64),true).contains("NaN"),"Empty transfer has invalid rate");
        try(AttachmentV2Tests.Pair pair=new AttachmentV2Tests.Pair()){
            var file=pair.root.resolve("summary.txt");java.nio.file.Files.writeString(file,"diagnostic test");
            pair.a.offer(new FileAttachmentSource(file),"summary.txt","text/plain").get(3,java.util.concurrent.TimeUnit.SECONDS);
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(!pair.a.diagnostics().contains("\"state\":\"delivered\"" )&&System.nanoTime()<end)Thread.sleep(10);
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
        System.out.println("TransferAttemptTests: real transfer summaries, monotonic duration, restored prefix, failed queue, empty file and idle freeze passed");
    }
}
