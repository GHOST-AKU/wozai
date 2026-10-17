package dev.ghost.nearbyim;
import android.content.Context;
import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
/** Native filesystem/Dex gate using a controlled test wire; not a radio or encryption test. */
final class AndroidAttachmentV2Checks {
    private static String hex(char value){char[] chars=new char[64];Arrays.fill(chars,value);return new String(chars);}
    static int run(Context context)throws Exception {
        Path root=Files.createTempDirectory(context.getCacheDir().toPath(),"native-v2-");AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        BlockingQueue<AttachmentRecord> saved=new LinkedBlockingQueue<>(),sent=new LinkedBlockingQueue<>();java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        try {
            long platformBytes=new android.os.StatFs(root.toString()).getAvailableBytes(),portableBytes=root.toFile().getUsableSpace();
            if(portableBytes<=0||Math.abs(platformBytes-portableBytes)>64L*1024*1024)throw new AssertionError("Portable free-space check differs from Android StatFs");
            for(int i=0;i<2;i++){final int side=i;peers[i]=new AttachmentTransferV2(root.resolve(i==0?"a":"b"),hex(i==0?'a':'b'),hex(i==0?'b':'a'),hex('c'),false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{ByteArrayOutputStream encoded=new ByteArrayOutputStream();TransferCodec.write(encoded,packet);peers[1-side].receive(TransferCodec.read(new ByteArrayInputStream(encoded.toByteArray())),true);return true;}catch(IOException error){failure.compareAndSet(null,error);return false;}}
                public void abort(){}
                public void abort(Throwable error){failure.compareAndSet(null,error);}
            },i==0?sent::add:saved::add);}
            byte[] bytes=new byte[TransferLimits.BLOCK_BYTES+3];new Random(27).nextBytes(bytes);Path original=root.resolve("source.bin");Files.write(original,bytes);
            peers[0].offer(new FileAttachmentSource(original),"native.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            AttachmentRecord received=await(saved,"received",failure);await(sent,"delivered",failure);
            if(!received.info.hash.equals(AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes))))throw new AssertionError("Native V2 digest changed");
            Path content=AttachmentTransfer.file(root.resolve("b"),received.info);if(!Arrays.equals(Files.readAllBytes(content),bytes))throw new AssertionError("Native V2 content changed");
            peers[0].shutdown().get(10,TimeUnit.SECONDS);peers[1].shutdown().get(10,TimeUnit.SECONDS);
            AttachmentTransfer.clean(root.resolve("b"),Collections.emptySet());if(!Files.exists(content))throw new AssertionError("Native cleanup removed durably committed content");
            return 4+queueReconnect(context);
        }finally{for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(10,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static int queueReconnect(Context context)throws Exception {
        Path root=Files.createTempDirectory(context.getCacheDir().toPath(),"native-queue-reconnect-");
        android.net.Uri uri=android.net.Uri.parse("content://dev.ghost.nearbyim.test.sources/file");context.getContentResolver().call(uri,"reset",null,null);
        try {
            try(NativeQueuePair pair=new NativeQueuePair(context,root,hex('d'))){pair.sender.shutdown().get(10,TimeUnit.SECONDS);}
            try(NativeQueuePair pair=new NativeQueuePair(context,root,hex('e'))){
                pair.sender.enqueue(Collections.singletonList(selected(uri,"fresh-after-idle.bin"))).get(10,TimeUnit.SECONDS);pair.received();
                pair.sender.pauseBatch().get(10,TimeUnit.SECONDS);pair.sender.enqueue(Arrays.asList(selected(uri,"old-one.bin"),selected(uri,"old-two.bin"))).get(10,TimeUnit.SECONDS);
                if(!pair.sender.batchItems().get(1).record().resumable()||!pair.sender.batchItems().get(2).record().resumable())throw new AssertionError("Native paused queue hides Continue");
                pair.sender.shutdown().get(10,TimeUnit.SECONDS);
            }
            try(NativeQueuePair pair=new NativeQueuePair(context,root,hex('f'))){
                pair.sender.enqueue(Collections.singletonList(selected(uri,"fresh-with-old-paused.bin"))).get(10,TimeUnit.SECONDS);AttachmentRecord received=pair.received();
                if(!received.info.name.equals("fresh-with-old-paused.bin"))throw new AssertionError("Fresh native send resumed an old paused selection");
                if(!pair.sender.batchItems().get(1).state().equals("paused")||!pair.sender.batchItems().get(2).state().equals("paused"))throw new AssertionError("Native reconnect lost existing paused states");
                pair.sender.resumeBatch().get(10,TimeUnit.SECONDS);pair.received();pair.received();
            }
            try(NativeQueuePair pair=new NativeQueuePair(context,root,hex('1'))){
                pair.sender.enqueue(Arrays.asList(selected(uri,"after-completed-one.bin"),selected(uri,"after-completed-two.bin"))).get(10,TimeUnit.SECONDS);pair.received();pair.received();
            }
            return 10;
        }finally{try(var paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static AttachmentBatchQueue.Selection selected(android.net.Uri uri,String name){return new AttachmentBatchQueue.Selection(uri.toString(),UUID.randomUUID().toString(),name,"application/octet-stream",-1,false);}
    private static final class NativeQueuePair implements AutoCloseable {
        final Path root;final AttachmentTransferV2[] peers=new AttachmentTransferV2[2];
        final BlockingQueue<AttachmentRecord> saved=new LinkedBlockingQueue<>(),sent=new LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final ClientAttachmentTransfers sender;
        NativeQueuePair(Context context,Path root,String connection)throws Exception {
            this.root=root;
            for(int i=0;i<2;i++){final int side=i;peers[i]=new AttachmentTransferV2(root.resolve(i==0?"a":"b"),hex(i==0?'a':'b'),hex(i==0?'b':'a'),connection,false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){try{ByteArrayOutputStream encoded=new ByteArrayOutputStream();TransferCodec.write(encoded,packet);peers[1-side].receive(TransferCodec.read(new ByteArrayInputStream(encoded.toByteArray())),true);return true;}catch(IOException error){failure.compareAndSet(null,error);return false;}}
                public void abort(){}public void abort(Throwable error){failure.compareAndSet(null,error);}
            },i==0?sent::add:saved::add);}
            AttachmentTransferV2.SourceResolver resumed=checkpoint->{AndroidAttachmentSource source=new AndroidAttachmentSource(context,android.net.Uri.parse(checkpoint.sourceReference().split("\n",2)[0]),checkpoint.key().sourceGeneration());if(!source.persistentReference().equals(checkpoint.sourceReference())){source.close();throw new IOException("Source changed");}return source;};
            sender=new ClientAttachmentTransfers(root.resolve("a"),peers[0],resumed,selection->new AndroidAttachmentSource(context,android.net.Uri.parse(selection.reference().split("\n",2)[0]),selection.generation()));
        }
        AttachmentRecord received()throws Exception {
            AttachmentRecord record=await(saved,"received",failure);await(sent,"delivered",failure);
            if(!Arrays.equals(Files.readAllBytes(AttachmentTransfer.file(root.resolve("b"),record.info)),new byte[]{0,1,2,3,4,5,6,7}))throw new AssertionError("Native reconnect changed ContentProvider bytes");
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(sender.batchItems().stream().anyMatch(item->item.info().id.equals(record.info.id)&&item.state().equals("delivered")))return record;Thread.sleep(5);}throw new AssertionError("Native queue missed saved delivery receipt");
        }
        public void close()throws Exception {sender.shutdown().get(10,TimeUnit.SECONDS);for(AttachmentTransferV2 peer:peers)peer.shutdown().get(10,TimeUnit.SECONDS);}
    }
    private static AttachmentRecord await(BlockingQueue<AttachmentRecord> records,String state,java.util.concurrent.atomic.AtomicReference<Throwable> failure)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);String observed="";
        while(System.nanoTime()<end){if(failure.get()!=null)throw new AssertionError("Native V2 pipeline failed after "+observed,failure.get());AttachmentRecord record=records.poll(100,TimeUnit.MILLISECONDS);if(record!=null){observed=record.state+":"+record.transferred;if(record.state.equals(state))return record;}}
        throw new AssertionError("Native V2 state missing: "+state+"; last="+observed,failure.get());
    }
}
