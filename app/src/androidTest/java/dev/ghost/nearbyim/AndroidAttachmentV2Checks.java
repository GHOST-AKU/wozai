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
            return 4;
        }finally{for(AttachmentTransferV2 peer:peers)if(peer!=null)peer.shutdown().get(10,TimeUnit.SECONDS);try(var paths=Files.walk(root)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static AttachmentRecord await(BlockingQueue<AttachmentRecord> records,String state,java.util.concurrent.atomic.AtomicReference<Throwable> failure)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);String observed="";
        while(System.nanoTime()<end){if(failure.get()!=null)throw new AssertionError("Native V2 pipeline failed after "+observed,failure.get());AttachmentRecord record=records.poll(100,TimeUnit.MILLISECONDS);if(record!=null){observed=record.state+":"+record.transferred;if(record.state.equals(state))return record;}}
        throw new AssertionError("Native V2 state missing: "+state+"; last="+observed,failure.get());
    }
}
