package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Reproductions of the independent whole-branch review findings. */
public final class AttachmentLifecycleReviewTests {
    private static final String A="a".repeat(64),B="b".repeat(64),CONNECTION="d".repeat(64);
    private record Delivery(boolean toA,TransferPacket packet) {}
    private static final class Pair implements AutoCloseable {
        final Path root;final AttachmentTransferV2 a,b;final TransferStorageBudget budget;
        final BlockingQueue<AttachmentRecord> ar=new LinkedBlockingQueue<>(),br=new LinkedBlockingQueue<>();
        final List<Delivery> held=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger aborted=new AtomicInteger();
        volatile boolean holdCredit,holdStatus,failOutgoingPause;
        Pair(Path root)throws Exception {
            this.root=root;budget=new TransferStorageBudget(root.resolve("a"),TransferStorageBudget.DEFAULT_QUOTA);
            AttachmentTransferV2[] endpoints=new AttachmentTransferV2[2];
            a=new AttachmentTransferV2(root.resolve("a"),A,B,CONNECTION,false,wire(endpoints,false),r->{if(failOutgoingPause&&r.outgoing&&r.state.equals("paused"))throw new IOException("Injected first-direction history failure");ar.add(r);},budget);
            b=new AttachmentTransferV2(root.resolve("b"),B,A,CONNECTION,false,wire(endpoints,true),br::add);
            endpoints[0]=a;endpoints[1]=b;
        }
        AttachmentTransferV2.Wire wire(AttachmentTransferV2[] endpoints,boolean toA){return new AttachmentTransferV2.Wire(){
            public boolean send(TransferPacket p){try{
                if(holdCredit&&p.kind==TransferPacket.Kind.CREDIT)return true;
                if(holdStatus&&p.kind==TransferPacket.Kind.STATUS){held.add(new Delivery(toA,p));return true;}
                endpoints[toA?0:1].receive(p,true);return true;
            }catch(IOException e){return false;}}
            public void abort(){aborted.incrementAndGet();}
        };}
        void deliver(boolean reverse)throws Exception {
            List<Delivery> packets; synchronized(held){packets=new ArrayList<>(held);held.clear();}
            holdStatus=false;if(reverse)Collections.reverse(packets);
            for(Delivery d:packets)(d.toA?a:b).receive(d.packet,true);
        }
        public void close(){for(AttachmentTransferV2 e:List.of(a,b))try{e.shutdown().get(5,TimeUnit.SECONDS);}catch(Exception ignored){}}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static AttachmentRecord waitFor(BlockingQueue<AttachmentRecord> records,String state)throws Exception{return AttachmentV2Tests.waitFor(records,state);}
    private static void dense(Path file,int blocks)throws Exception {byte[] block=new byte[TransferLimits.BLOCK_BYTES];new Random(99).nextBytes(block);try(OutputStream out=Files.newOutputStream(file)){for(int i=0;i<blocks;i++)out.write(block);}}
    private static TransferTaskKey key(boolean sender,String id,String generation){return new TransferTaskKey(sender?A:B,sender?B:A,sender?TransferTaskKey.Direction.SEND:TransferTaskKey.Direction.RECEIVE,id,generation);}
    private static void shutdown(Path root)throws Exception {
        Files.createDirectories(root);Path file=root.resolve("source.bin");dense(file,8);
        try(Pair p=new Pair(root)) {
            p.holdCredit=true;p.a.offer(new FileAttachmentSource(file),"a.bin","application/octet-stream").get();p.b.offer(new FileAttachmentSource(file),"b.bin","application/octet-stream").get();
            waitFor(p.ar,"transferring");waitFor(p.br,"transferring");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(p.budget.reservedBytes()==0&&System.nanoTime()<deadline)Thread.sleep(5);
            check(p.budget.reservedBytes()>0,"Fixture did not reserve incoming capacity");p.failOutgoingPause=true;
            try{p.a.shutdown().get(5,TimeUnit.SECONDS);throw new AssertionError("Persistence failure was hidden");}catch(ExecutionException expected){}
            check(p.budget.reservedBytes()==0,"First-direction pause failure leaked the opposite reservation");
            check(new TransferCheckpointStore(root.resolve("a/.tasks-v2")).list().stream().allMatch(c->c.state()==TransferCheckpoint.State.PAUSED),"Shutdown skipped the second pause checkpoint");
            try(AttachmentTransferV2 replacement=new AttachmentTransferV2(root.resolve("a"),A,B,"e".repeat(64),false,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){return true;}public void abort(){}},r->{},p.budget)){check(replacement.queuedBytes()==0,"Old writer overlapped the replacement");}
        }
    }
    private static void crossed(Path root,boolean reverse)throws Exception {
        Files.createDirectories(root);Path file=root.resolve("source.bin");dense(file,4);String id,generation;
        try(Pair p=new Pair(root)) {p.holdCredit=true;FileAttachmentSource source=new FileAttachmentSource(file);generation=source.generation();id=p.a.offer(source,"source.bin","application/octet-stream").get();waitFor(p.ar,"transferring");p.a.pause(id,true).get();waitFor(p.br,"paused");}
        try(Pair p=new Pair(root)) {
            p.holdStatus=true;p.a.resume(key(true,id,generation),new FileAttachmentSource(file,generation)).get();p.b.resume(key(false,id,generation),null).get();p.b.resume(key(false,id,generation),null).get();
            p.a.receive(TransferPacket.progress(TransferPacket.Kind.CREDIT,id,generation,CONNECTION,4L*TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,0,TransferLimits.BLOCK_BYTES,null),true);
            p.a.resume(key(true,id,generation),new FileAttachmentSource(file,generation)).get();
            p.deliver(reverse);waitFor(p.ar,"delivered");AttachmentRecord saved=waitFor(p.br,"received");
            check(p.aborted.get()==0&&Files.mismatch(file,AttachmentTransfer.file(root.resolve("b"),saved.info))==-1,"Crossed/repeated resume aborted or changed content");
        }
    }
    private static void source(Path root)throws Exception {
        Files.createDirectories(root);Path file=root.resolve("source.bin");dense(file,40);
        try(Pair p=new Pair(root)) {
            FileAttachmentSource original=new FileAttachmentSource(file);String generation=original.generation();AtomicLong reads=new AtomicLong();
            AttachmentSource flaky=new AttachmentSource(){
                public long size(){return original.size();}public String generation(){return generation;}public boolean seekable(){return true;}public String persistentReference(){return original.persistentReference();}
                public void verifyUnchanged()throws IOException{original.verifyUnchanged();}public void close()throws IOException{original.close();}
                public InputStream open(long offset)throws IOException{return new FilterInputStream(original.open(offset)){public int read(byte[] b,int o,int n)throws IOException{if(reads.get()>=33L*TransferLimits.BLOCK_BYTES)throw new IOException("Provider temporarily unavailable");int count=in.read(b,o,n);if(count>0)reads.addAndGet(count);return count;}};}
            };
            String id=p.a.offer(flaky,"source.bin","application/octet-stream").get();AttachmentRecord paused=waitFor(p.ar,"sourceUnavailable");waitFor(p.br,"paused");
            check(paused.resumable()&&AttachmentRecord.decode(paused.encode()).state.equals("sourceUnavailable"),"Source error reason/resume action was not persisted");
            TransferCheckpoint receiver=new TransferCheckpointStore(root.resolve("b/.tasks-v2")).load(key(false,id,generation)).orElseThrow();
            check(receiver.state()==TransferCheckpoint.State.PAUSED&&receiver.durableOffset()>=32L*TransferLimits.BLOCK_BYTES&&Files.isRegularFile(root.resolve("b").resolve(receiver.key().fileName()+".part")),"Source failure discarded the durable receiver prefix");
            p.a.resume(key(true,id,generation),new FileAttachmentSource(file,generation)).get();waitFor(p.ar,"delivered");AttachmentRecord saved=waitFor(p.br,"received");
            check(p.aborted.get()==0&&Files.mismatch(file,AttachmentTransfer.file(root.resolve("b"),saved.info))==-1,"Restored source did not resume exact content");
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("review-lifecycle-");String test=args.length==0?"all":args[0];
        try {if(test.equals("all")||test.equals("shutdown"))shutdown(root.resolve("shutdown"));if(test.equals("all")||test.equals("crossed")){crossed(root.resolve("crossed-a"),false);crossed(root.resolve("crossed-b"),true);}if(test.equals("all")||test.equals("source"))source(root.resolve("source"));System.out.println("Independent review regressions: "+test+" passed");}
        finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
