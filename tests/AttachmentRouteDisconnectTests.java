package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Losing an independent FILE socket must retain a live, locally paused engine. */
public final class AttachmentRouteDisconnectTests {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        java.lang.reflect.Method pause;
        try{pause=ClientAttachmentTransfers.class.getMethod("pauseOnDisconnect");}
        catch(NoSuchMethodException missing){throw new AssertionError("File-channel loss has no local pause API; notifying the broken wire closes the engine",missing);}
        Path root=Files.createTempDirectory("file-route-loss-");
        AtomicBoolean offline=new AtomicBoolean();AtomicInteger sends=new AtomicInteger(),aborts=new AtomicInteger();
        BlockingQueue<AttachmentRecord> records=new LinkedBlockingQueue<>();
        try {
            Path source=root.resolve("source.bin");Files.write(source,new byte[1024]);
            Path directory=root.resolve("peer");
            AttachmentTransferV2 engine=new AttachmentTransferV2(directory,"a".repeat(64),"b".repeat(64),"c".repeat(64),false,
                    new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){sends.incrementAndGet();return !offline.get();}public void abort(){aborts.incrementAndGet();}public boolean recoverableDisconnect(){return true;}},records::add);
            try(ClientAttachmentTransfers files=new ClientAttachmentTransfers(directory,engine,checkpoint->new FileAttachmentSource(source,checkpoint.key().sourceGeneration()))) {
                files.enqueue(List.of(AttachmentBatchQueue.selectFile(source),AttachmentBatchQueue.selectFile(source))).get(3,TimeUnit.SECONDS);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean offered=false;
                while(System.nanoTime()<deadline){AttachmentRecord record=records.poll(20,TimeUnit.MILLISECONDS);if(record!=null&&record.state.equals("offered")){offered=true;break;}}
                check(offered,"Fixture did not start the first queued file");
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(sends.get()==0&&System.nanoTime()<deadline)Thread.sleep(5);
                check(sends.get()==1,"Fixture did not put its initial offer on the live lane");
                offline.set(true);int before=sends.get();
                @SuppressWarnings("unchecked") CompletableFuture<Void> stopped=(CompletableFuture<Void>)pause.invoke(files);
                stopped.get(3,TimeUnit.SECONDS);
                check(sends.get()==before&&aborts.get()==0,"Local disconnect pause sent to a dead lane or aborted the engine");
                check(files.batchItems().size()==2&&files.batchItems().stream().allMatch(item->item.state().equals("paused")),"Disconnect pause lost active or queued files");
                check(new TransferCheckpointStore(directory.resolve(".tasks-v2")).list().stream().allMatch(task->task.state()==TransferCheckpoint.State.PAUSED),"Disconnect pause failed to retain durable task state");
                // The selected lane may disappear between admission and the resume STATUS.
                files.resumeBatch().get(3,TimeUnit.SECONDS);
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(sends.get()==before&&System.nanoTime()<deadline)Thread.sleep(5);
                check(sends.get()>before,"Fixture did not race the resume STATUS against a lost lane");
                files.pauseOnDisconnect().get(3,TimeUnit.SECONDS);
                check(files.batchItems().stream().allMatch(item->item.state().equals("paused")),"Resume STATUS loss incorrectly makes durable work terminal/failed");
                check(aborts.get()==0,"Resume STATUS loss closed a recoverable engine");
                before=sends.get();
                offline.set(false);files.resumeBatch().get(3,TimeUnit.SECONDS);
                deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(sends.get()==before&&System.nanoTime()<deadline)Thread.sleep(5);
                check(sends.get()>before&&aborts.get()==0,"Engine cannot resume after its file lane is replaced");
            }
            System.out.println("Attachment route disconnect: active/queued checkpoints, no broken-wire write, live resume passed");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
