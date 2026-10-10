package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class AttachmentV2Tests {
    private static int checks;
    static final String CONNECTION="c".repeat(64);
    static final class Pair implements AutoCloseable {
        final Path root;
        final boolean cleanup;
        final BlockingQueue<AttachmentRecord> aRecords=new LinkedBlockingQueue<>(),bRecords=new LinkedBlockingQueue<>();
        final AttachmentTransferV2 a,b;
        volatile boolean holdCredit,corruptData,corruptEnd,dropComplete,failSavedHistory;
        volatile TransferPacket lastData,lastEnd;
        final AtomicLong dataBytes=new AtomicLong(),sourceReads=new AtomicLong(),firstReadCount=new AtomicLong(-1);
        final AtomicInteger completeCount=new AtomicInteger();
        volatile TransferPacket heldCredit;
        Pair()throws Exception {this(Files.createTempDirectory("v2-pipeline-"),true,CONNECTION);}
        Pair(Path root,boolean cleanup,String connection)throws Exception {
            this.root=root;this.cleanup=cleanup;
            AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
            a=new AttachmentTransferV2(root.resolve("a"),"a".repeat(64),"b".repeat(64),connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{if(packet.kind==TransferPacket.Kind.DATA){lastData=packet;dataBytes.addAndGet(packet.data.length);firstReadCount.compareAndSet(-1,sourceReads.get());if(corruptData)packet.data[0]^=1;}if(packet.kind==TransferPacket.Kind.END)lastEnd=packet;if(corruptEnd&&packet.kind==TransferPacket.Kind.END)packet.hash[0]^=1;peers[1].receive(packet,true);return true;}catch(IOException e){return false;}}
                public void abort(){}
            },aRecords::add);
            b=new AttachmentTransferV2(root.resolve("b"),"b".repeat(64),"a".repeat(64),connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{if(packet.kind==TransferPacket.Kind.COMPLETE){completeCount.incrementAndGet();if(dropComplete)return true;}if(holdCredit&&packet.kind==TransferPacket.Kind.CREDIT){heldCredit=packet;return true;}peers[0].receive(packet,true);return true;}catch(IOException e){return false;}}
                public void abort(){}
            },record->{if(failSavedHistory&&record.state.equals("received"))throw new IOException("Injected final history failure");bRecords.add(record);});
            peers[0]=a;peers[1]=b;
        }
        void releaseCredit()throws IOException{holdCredit=false;TransferPacket packet=heldCredit;if(packet!=null)a.receive(packet,true);}
        public void close()throws Exception {a.shutdown().get(5,TimeUnit.SECONDS);b.shutdown().get(5,TimeUnit.SECONDS);if(cleanup)try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    public static void main(String[] args)throws Exception {
        Set<Long> existingThreads=new HashSet<>();for(Thread thread:Thread.getAllStackTraces().keySet())existingThreads.add(thread.getId());
        try(Pair p=new Pair()) {
            Thread.sleep(30);check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread->!existingThreads.contains(thread.getId())&&thread.getName().startsWith("attachment-v2-")),"Idle session started file-transfer workers");
            Path source=p.root.resolve("source.bin");byte[] content=new byte[2*TransferLimits.BLOCK_BYTES+17];new Random(32).nextBytes(content);Files.write(source,content);
            FileAttachmentSource original=new FileAttachmentSource(source);
            AttachmentSource counted=new AttachmentSource(){
                public long size(){return original.size();}public String generation(){return original.generation();}public boolean seekable(){return true;}
                public void verifyUnchanged()throws IOException{original.verifyUnchanged();}public void close()throws IOException{original.close();}
                public InputStream open(long offset)throws IOException{return new FilterInputStream(original.open(offset)){
                    public int read(byte[] bytes,int start,int length)throws IOException{int n=in.read(bytes,start,length);if(n>0)p.sourceReads.addAndGet(n);return n;}
                };}
            };
            p.a.offer(counted,"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            AttachmentRecord received=waitFor(p.bRecords,"received"),delivered=waitFor(p.aRecords,"delivered");
            check(Arrays.equals(content,Files.readAllBytes(AttachmentTransfer.file(p.root.resolve("b"),received.info))),"Received bytes differ");
            check(p.firstReadCount.get()==TransferLimits.DATA_BYTES,"First data waited for an entire source scan");
            check(received.info.hash.equals(AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(content)))&&delivered.info.hash.equals(received.info.hash),"Final digest differs");
            check(p.a.queuedBytes()<=8L*1024*1024&&p.b.queuedBytes()<=8L*1024*1024,"Queue budget exceeded");
            Path empty=p.root.resolve("empty");Files.createFile(empty);p.b.offer(new FileAttachmentSource(empty),"empty.txt","text/plain").get(3,TimeUnit.SECONDS);
            check(waitFor(p.aRecords,"received").info.size==0,"Reverse empty transfer failed");waitFor(p.bRecords,"delivered");
        }
        try(Pair p=new Pair()) {
            Path source=p.root.resolve("photo.jpg");byte[] original={1,2,3,4,5};Files.write(source,original);
            p.a.offer(new FileAttachmentSource(source),"photo.jpg","image/jpeg").get(3,TimeUnit.SECONDS);
            AttachmentRecord sent=waitFor(p.aRecords,"delivered");waitFor(p.bRecords,"received");
            check(Files.isRegularFile(AttachmentTransfer.file(p.root.resolve("a"),sent.info,true)),"V2 discarded the sent photo preview");
            check(Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(p.root.resolve("a"),sent.info,true)),original)&&Files.exists(source),"Sent preview changed bytes or removed the original");
        }
        try(Pair p=new Pair()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            AttachmentSource blocked=new AttachmentSource(){
                public long size(){return 0;}public String generation(){return UUID.randomUUID().toString();}public boolean seekable(){return true;}
                public InputStream open(long offset){return new ByteArrayInputStream(new byte[0]);}
                public void verifyUnchanged()throws IOException{entered.countDown();try{if(!release.await(3,TimeUnit.SECONDS))throw new IOException("Blocked source");}catch(InterruptedException error){throw new IOException(error);}}
                public void close(){release.countDown();}
            };
            p.a.offer(blocked,"first.bin","application/octet-stream");check(entered.await(2,TimeUnit.SECONDS),"Source did not enter preparation");
            Path source=p.root.resolve("queued.bin");Files.createFile(source);
            CompletableFuture<String> pending=p.a.offer(new FileAttachmentSource(source),"queued.bin","application/octet-stream");
            p.a.close();release.countDown();
            try{pending.get(1,TimeUnit.SECONDS);}catch(ExecutionException expected){}catch(TimeoutException failure){throw new AssertionError("Closing left a queued offer unresolved",failure);}
            check(pending.isDone(),"Queued source future retained");
        }
        try(Pair p=new Pair()) {
            p.corruptEnd=true;Path source=p.root.resolve("source.bin");Files.write(source,new byte[10]);
            p.a.offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            waitFor(p.bRecords,"failed");waitFor(p.aRecords,"failed");checks++;
        }
        try(Pair p=new Pair()) {
            Path source=p.root.resolve("source.bin");Files.write(source,new byte[2*TransferLimits.BLOCK_BYTES+1]);
            p.a.offer(new FileAttachmentSource(source),"a.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            p.b.offer(new FileAttachmentSource(source),"b.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            for(BlockingQueue<AttachmentRecord> records:List.of(p.aRecords,p.bRecords)) {
                Set<String> terminal=new HashSet<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while(terminal.size()<2&&System.nanoTime()<deadline){AttachmentRecord record=records.poll(100,TimeUnit.MILLISECONDS);if(record!=null&&Set.of("received","delivered").contains(record.state))terminal.add(record.state);}
                check(terminal.size()==2,"Simultaneous duplex transfer did not complete");
            }
        }
        try(Pair p=new Pair()) {
            p.holdCredit=true;Path source=p.root.resolve("source.bin");Files.write(source,new byte[2*TransferLimits.BLOCK_BYTES]);
            p.a.offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(p.dataBytes.get()<TransferLimits.BLOCK_BYTES&&System.nanoTime()<deadline)Thread.sleep(5);
            Thread.sleep(40);check(p.dataBytes.get()==TransferLimits.BLOCK_BYTES,"Sender exceeded withheld credit");
            p.releaseCredit();waitFor(p.bRecords,"received");waitFor(p.aRecords,"delivered");
        }
        try(Pair p=new Pair()) {
            p.corruptData=true;Path source=p.root.resolve("source.bin");Files.write(source,new byte[40_000]);
            p.a.offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            waitFor(p.bRecords,"failed");waitFor(p.aRecords,"failed");
            try(var paths=Files.list(p.root.resolve("b"))){check(paths.noneMatch(path->!path.getFileName().toString().equals(".tasks-v2")),"Corrupted content retained");}
        }
        try(Pair p=new Pair()) {
            p.holdCredit=true;Path source=p.root.resolve("source.bin");Files.write(source,new byte[2*TransferLimits.BLOCK_BYTES]);
            String id=p.a.offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            waitFor(p.aRecords,"transferring");p.a.cancel(id,true);waitFor(p.aRecords,"canceled");waitFor(p.bRecords,"canceled");
            p.a.shutdown().get(3,TimeUnit.SECONDS);check(p.a.queuedBytes()==0&&p.a.retainedBufferBytes()==0,"Canceled pipeline retained buffers");
        }
        System.out.println("AttachmentV2Tests: "+checks+" pipeline checks passed");
    }
    static AttachmentRecord waitFor(BlockingQueue<AttachmentRecord> records,String state)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<deadline){AttachmentRecord record=records.poll(100,TimeUnit.MILLISECONDS);if(record!=null&&record.state.equals(state))return record;}
        throw new AssertionError("Missing state "+state);
    }
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
}
