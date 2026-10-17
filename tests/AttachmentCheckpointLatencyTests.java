package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Exercises real forced content, checkpoints and receipts under slow storage. */
public final class AttachmentCheckpointLatencyTests {
    public static void main(String[] args)throws Exception {
        if(args.length>0){transfer(12,16,2000,2);return;}
        transfer(32,0,0,1);
        transfer(0,0,0,1);
        transfer(12,16,2000,2);
        transfer(96,0,2000,3);
    }
    private static void transfer(int mib,int readDelay,int forceDelay,int expectedForces)throws Exception {
        Path root=Files.createTempDirectory("checkpoint-latency-");
        AttachmentTransferV2[] peers=new AttachmentTransferV2[2];AtomicInteger forces=new AtomicInteger();AtomicBoolean installed=new AtomicBoolean();
        CompletableFuture<AttachmentRecord> received=new CompletableFuture<>(),delivered=new CompletableFuture<>();
        try {
            Path source=root.resolve("source.bin");byte[] block=new byte[TransferLimits.BLOCK_BYTES];new Random(19).nextBytes(block);
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(OutputStream out=Files.newOutputStream(source)){for(int n=0;n<mib;n++){out.write(block);digest.update(block);}}
            String expectedHash=AttachmentInfo.hex(digest.digest());
            peers[0]=new AttachmentTransferV2(root.resolve("a"),"a".repeat(64),"b".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,1),r->{if(r.state.equals("delivered"))delivered.complete(r);});
            peers[1]=new AttachmentTransferV2(root.resolve("b"),"b".repeat(64),"a".repeat(64),AttachmentV2Tests.CONNECTION,false,wire(peers,0),r->{
                if(r.state.equals("transferring")&&installed.compareAndSet(false,true))try {
                    Field incoming=AttachmentTransferV2.class.getDeclaredField("incoming");incoming.setAccessible(true);Object task=incoming.get(peers[1]);
                    Field output=task.getClass().getDeclaredField("output");output.setAccessible(true);
                    output.set(task,new SlowForceChannel((FileChannel)output.get(task),task,forces,forceDelay));
                }catch(ReflectiveOperationException e){throw new IOException(e);}
                if(r.state.equals("received"))received.complete(r);
            });
            FileAttachmentSource file=new FileAttachmentSource(source);
            AttachmentSource input=new AttachmentSource(){
                public long size(){return file.size();}public boolean seekable(){return true;}public String generation(){return file.generation();}
                public String persistentReference(){return file.persistentReference();}public void verifyUnchanged()throws IOException{file.verifyUnchanged();}public void close()throws IOException{file.close();}
                public InputStream open(long offset)throws IOException{return new FilterInputStream(file.open(offset)){
                    public int read(byte[] data,int start,int count)throws IOException{if(readDelay>0)delay(readDelay);return in.read(data,start,count);}
                };}
            };
            peers[0].offer(input,"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            AttachmentRecord end=received.get(30,TimeUnit.SECONDS),ack=delivered.get(3,TimeUnit.SECONDS);
            Path saved=AttachmentTransfer.file(root.resolve("b"),end.info);MessageDigest actual=MessageDigest.getInstance("SHA-256");
            try(InputStream in=Files.newInputStream(saved)){int count;while((count=in.read(block))!=-1)actual.update(block,0,count);}
            if(Files.size(saved)!=(long)mib*TransferLimits.BLOCK_BYTES||!expectedHash.equals(AttachmentInfo.hex(actual.digest()))||!expectedHash.equals(ack.info.hash))throw new AssertionError("Forced file/receipt changed");
            if(forces.get()!=expectedForces)throw new AssertionError(mib+" MiB, read delay "+readDelay+", force delay "+forceDelay+": forced content "+forces.get()+" times; expected "+expectedForces);
            System.out.println(mib+" MiB slow-storage case: "+forces.get()+" content forces; final SHA-256/receipt and 32 MiB durable bound verified");
        }finally {
            for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(10,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static void delay(int ms)throws IOException{try{Thread.sleep(ms);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
    private static AttachmentTransferV2.Wire wire(AttachmentTransferV2[] peers,int index){return new AttachmentTransferV2.Wire(){
        public boolean send(TransferPacket packet){try{peers[index].receive(packet,true);return true;}catch(IOException e){return false;}}
        public void abort(Throwable failure){throw new AssertionError(failure);}public void abort(){throw new AssertionError("Unexpected abort");}
    };}
    private static final class SlowForceChannel extends FileChannel {
        final FileChannel delegate;final Object task;final AtomicInteger count;final int delay;
        SlowForceChannel(FileChannel delegate,Object task,AtomicInteger count,int delay){this.delegate=delegate;this.task=task;this.count=count;this.delay=delay;}
        public void force(boolean metadata)throws IOException {
            try{Field written=task.getClass().getDeclaredField("written"),durable=task.getClass().getDeclaredField("durable");written.setAccessible(true);durable.setAccessible(true);
                if(written.getLong(task)-durable.getLong(task)>32L*TransferLimits.BLOCK_BYTES)throw new AssertionError("Content force exceeded 32 MiB rollback bound");
            }catch(ReflectiveOperationException e){throw new IOException(e);}
            count.incrementAndGet();if(delay>0)delay(delay);delegate.force(metadata);
        }
        public int read(ByteBuffer b)throws IOException{return delegate.read(b);}public long read(ByteBuffer[] b,int o,int n)throws IOException{return delegate.read(b,o,n);}
        public int write(ByteBuffer b)throws IOException{return delegate.write(b);}public long write(ByteBuffer[] b,int o,int n)throws IOException{return delegate.write(b,o,n);}
        public long position()throws IOException{return delegate.position();}public FileChannel position(long p)throws IOException{delegate.position(p);return this;}
        public long size()throws IOException{return delegate.size();}public FileChannel truncate(long n)throws IOException{delegate.truncate(n);return this;}
        public long transferTo(long p,long n,WritableByteChannel c)throws IOException{return delegate.transferTo(p,n,c);}
        public long transferFrom(ReadableByteChannel c,long p,long n)throws IOException{return delegate.transferFrom(c,p,n);}
        public int read(ByteBuffer b,long p)throws IOException{return delegate.read(b,p);}public int write(ByteBuffer b,long p)throws IOException{return delegate.write(b,p);}
        public MappedByteBuffer map(MapMode m,long p,long n)throws IOException{return delegate.map(m,p,n);}
        public FileLock lock(long p,long n,boolean s)throws IOException{return delegate.lock(p,n,s);}public FileLock tryLock(long p,long n,boolean s)throws IOException{return delegate.tryLock(p,n,s);}
        protected void implCloseChannel()throws IOException{delegate.close();}
    }
}
