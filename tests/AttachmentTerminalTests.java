package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Completion means the durable task no longer occupies its session's slot. */
public final class AttachmentTerminalTests {
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("attachment-terminal-");
        AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        BlockingQueue<Boolean> sender=new LinkedBlockingQueue<>(),receiver=new LinkedBlockingQueue<>();
        BlockingQueue<AttachmentRecord> receivedRecords=new LinkedBlockingQueue<>();AtomicInteger aborted=new AtomicInteger(),discarded=new AtomicInteger();
        AtomicReference<AttachmentRecord> lastSender=new AtomicReference<>(),lastReceiver=new AtomicReference<>();
        AtomicReference<Exception> wireFailure=new AtomicReference<>();
        String a="a".repeat(64),b="b".repeat(64),connection="c".repeat(64);
        try {
            peers[0]=new AttachmentTransferV2(root.resolve("a"),a,b,connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{peers[1].receive(packet,true);return true;}catch(Exception e){wireFailure.compareAndSet(null,e);return false;}}
                public void abort(){aborted.incrementAndGet();}
            },record->{lastSender.set(record);if(record.state.equals("delivered"))sender.add(!peers[0].hasActive());});
            peers[1]=new AttachmentTransferV2(root.resolve("b"),b,a,connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{peers[0].receive(packet,true);return true;}catch(Exception e){wireFailure.compareAndSet(null,e);return false;}}
                public void abort(){aborted.incrementAndGet();}
            },record->{lastReceiver.set(record);if(record.state.equals("received")){receivedRecords.add(record);receiver.add(!peers[1].hasActive());}});
            Path original=root.resolve("original.bin");Files.write(original,new byte[131075]);
            peers[0].offer(new FileAttachmentSource(original),"original.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            Boolean released=sender.poll(5,TimeUnit.SECONDS);
            if(!Boolean.TRUE.equals(released))throw new AssertionError("Delivered event still occupies the sender slot: "+released
                    +"; sender="+describe(lastSender.get())+"; receiver="+describe(lastReceiver.get())+"; aborts="+aborted.get(),wireFailure.get());
            if(!Boolean.TRUE.equals(receiver.poll(3,TimeUnit.SECONDS)))throw new AssertionError("Received event still occupies the receiver slot");
            if(!Files.exists(original))throw new AssertionError("Delivery removed user original");
            receivedRecords.remove();
            FileAttachmentSource delegate=new FileAttachmentSource(original);
            AttachmentSource failingCleanup=new AttachmentSource(){
                public long size(){return delegate.size();}public String generation(){return delegate.generation();}public boolean seekable(){return true;}
                public String persistentReference(){return delegate.persistentReference();}public void verifyUnchanged()throws IOException{delegate.verifyUnchanged();}
                public InputStream open(long offset)throws IOException{return delegate.open(offset);}public void close()throws IOException{delegate.close();}
                public void discard()throws IOException{discarded.incrementAndGet();delegate.discard();throw new IOException("Injected optional grant cleanup failure");}
            };
            peers[0].offer(failingCleanup,"cleanup.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            if(!Boolean.TRUE.equals(sender.poll(5,TimeUnit.SECONDS)))throw new AssertionError("Source cleanup error hid an authenticated saved receipt or kept the sender slot");
            if(!Boolean.TRUE.equals(receiver.poll(3,TimeUnit.SECONDS)))throw new AssertionError("Source cleanup error kept receiver slot");
            AttachmentRecord saved=receivedRecords.poll(3,TimeUnit.SECONDS);
            if(saved==null||Files.mismatch(original,AttachmentTransfer.file(root.resolve("b"),saved.info))!=-1||discarded.get()!=1||aborted.get()!=0)throw new AssertionError("Optional cleanup failure changed saved bytes, skipped cleanup or aborted the session");
            peers[0].offer(new FileAttachmentSource(original),"next.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            if(!Boolean.TRUE.equals(sender.poll(5,TimeUnit.SECONDS))||!Boolean.TRUE.equals(receiver.poll(3,TimeUnit.SECONDS)))throw new AssertionError("Cleanup failure prevented the next file from completing");
            System.out.println("AttachmentTerminalTests: durable slots released before final history; cleanup failure preserves saved receipt, original bytes and next-file delivery");
        } finally {
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static String describe(AttachmentRecord record){return record==null?"none":record.state+" "+record.transferred+"/"+record.info.size;}
}
