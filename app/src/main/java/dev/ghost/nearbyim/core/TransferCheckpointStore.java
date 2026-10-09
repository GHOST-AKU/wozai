package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

/** Checksummed metadata and a bounded disk block index. Caller forces content first. */
public final class TransferCheckpointStore {
    private static final int MAGIC=0x54435031,MAX_CHECKPOINT=24*1024,INDEX_RECORD=40;
    private final Path root;
    public TransferCheckpointStore(Path privateRoot)throws IOException {
        root=privateRoot;
        if(Files.isSymbolicLink(root)||Files.exists(root,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe checkpoint directory");
        Files.createDirectories(root);permissions(root,"rwx------");
    }
    private Path entry(TransferTaskKey key,String suffix)throws IOException {
        Path file=root.resolve(key.fileName()+suffix);if(Files.isSymbolicLink(file)||Files.exists(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe checkpoint entry");return file;
    }
    public synchronized Optional<TransferCheckpoint> load(TransferTaskKey key)throws IOException {
        Path file=entry(key,".checkpoint");if(!Files.exists(file))return Optional.empty();byte[] encoded=readCheckpoint(file);
        byte[] payload=Arrays.copyOf(encoded,encoded.length-32),checksum=Arrays.copyOfRange(encoded,encoded.length-32,encoded.length);
        if(!MessageDigest.isEqual(sha().digest(payload),checksum))throw new IOException("Checkpoint checksum mismatch");
        try {
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(payload));if(in.readInt()!=MAGIC)throw new IOException("Unsupported checkpoint");
            String local=in.readUTF(),remote=in.readUTF();int direction=in.readUnsignedByte();if(direction>=TransferTaskKey.Direction.values().length)throw new IOException("Invalid checkpoint direction");
            TransferTaskKey stored=new TransferTaskKey(local,remote,TransferTaskKey.Direction.values()[direction],in.readUTF(),in.readUTF());if(!stored.equals(key))throw new IOException("Checkpoint task identity mismatch");
            AttachmentInfo info=AttachmentInfo.readV2(in);String reference=in.readUTF();long written=in.readLong(),verified=in.readLong(),durable=in.readLong();int state=in.readUnsignedByte();
            if(state>=TransferCheckpoint.State.values().length)throw new IOException("Invalid checkpoint state");TransferCheckpoint result=new TransferCheckpoint(stored,info,reference,written,verified,durable,TransferCheckpoint.State.values()[state],in.readLong());
            if(in.available()!=0)throw new IOException("Trailing checkpoint data");return Optional.of(result);
        }catch(IllegalArgumentException error){throw new IOException("Invalid checkpoint metadata",error);}
    }
    public synchronized void checkpoint(TransferCheckpoint value)throws IOException {
        Optional<TransferCheckpoint> existing=load(value.key());
        if(existing.isPresent()) {
            TransferCheckpoint previous=existing.get();AttachmentInfo old=previous.info(),next=value.info();
            if(previous.state()==TransferCheckpoint.State.CANCELED&&value.state()!=TransferCheckpoint.State.CANCELED
                    ||previous.state()==TransferCheckpoint.State.COMPLETE&&value.state()!=TransferCheckpoint.State.COMPLETE&&value.state()!=TransferCheckpoint.State.CANCELED
                    ||old.size!=next.size||old.time!=next.time||!old.name.equals(next.name)||!old.mime.equals(next.mime)||!previous.sourceReference().equals(value.sourceReference())
                    ||old.hash!=null&&!old.hash.equals(next.hash))throw new IOException("Conflicting or terminal checkpoint update");
            if(value.state()!=TransferCheckpoint.State.CANCELED&&value.durableOffset()<previous.durableOffset())throw new IOException("Durable checkpoint regressed");
        }
        Path index=entry(value.key(),".blocks");long blocks=(value.durableOffset()+TransferLimits.BLOCK_BYTES-1)/TransferLimits.BLOCK_BYTES;
        if(blocks>0&&(!Files.exists(index)||Files.size(index)<blocks*INDEX_RECORD))throw new IOException("Checkpoint block index missing");
        if(Files.exists(index))try(FileChannel channel=FileChannel.open(index,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);TransferTaskKey key=value.key();
        out.writeInt(MAGIC);out.writeUTF(key.localRoot());out.writeUTF(key.remoteRoot());out.writeByte(key.direction().ordinal());out.writeUTF(key.transferId());out.writeUTF(key.sourceGeneration());
        value.info().writeV2(out);out.writeUTF(value.sourceReference());out.writeLong(value.writtenOffset());out.writeLong(value.verifiedOffset());out.writeLong(value.durableOffset());out.writeByte(value.state().ordinal());out.writeLong(value.updatedMillis());
        byte[] payload=bytes.toByteArray();if(payload.length+32>MAX_CHECKPOINT)throw new IOException("Checkpoint too large");
        Path temporary=Files.createTempFile(root,"checkpoint-",".tmp");
        try {
            permissions(temporary,"rw-------");try(FileOutputStream file=new FileOutputStream(temporary.toFile())){file.write(payload);file.write(sha().digest(payload));file.getChannel().force(true);}
            Files.move(temporary,entry(key,".checkpoint"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(root);
        }finally{Files.deleteIfExists(temporary);}
    }
    public synchronized void appendBlock(TransferTaskKey key,long endOffset,byte[] hash)throws IOException {
        if(endOffset<1||endOffset>TransferLimits.MAX_FILE_BYTES||hash.length!=32)throw new IOException("Invalid block index record");Path path=entry(key,".blocks");
        long records=Files.exists(path)?Files.size(path)/INDEX_RECORD:0;
        if(Files.exists(path)&&Files.size(path)%INDEX_RECORD!=0||endOffset<=records*TransferLimits.BLOCK_BYTES||endOffset>(records+1)*TransferLimits.BLOCK_BYTES)throw new IOException("Non-contiguous block index");
        try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(path,StandardOpenOption.CREATE,StandardOpenOption.APPEND,LinkOption.NOFOLLOW_LINKS))){out.writeLong(endOffset);out.write(hash);}permissions(path,"rw-------");
    }
    public synchronized void truncateIndex(TransferTaskKey key,long offset)throws IOException {
        TransferLimits.validateRange(TransferLimits.MAX_FILE_BYTES,offset,0);Path path=entry(key,".blocks");
        if(Files.exists(path))try(FileChannel channel=FileChannel.open(path,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.truncate(((offset+TransferLimits.BLOCK_BYTES-1)/TransferLimits.BLOCK_BYTES)*INDEX_RECORD);channel.force(true);}
    }
    public record PrefixResult(MessageDigest whole,byte[] sha256,long offset){}
    @FunctionalInterface public interface PrefixProgress {void checked(long bytes)throws IOException;}
    /** Rehash cannot replace the previous trusted index until its prefix hash is checked. */
    public IndexRebuild rebuild(TransferTaskKey key,long expectedEnd)throws IOException {TransferLimits.validateRange(TransferLimits.MAX_FILE_BYTES,expectedEnd,0);return new IndexRebuild(key,expectedEnd);}
    public final class IndexRebuild implements AutoCloseable {
        private final TransferTaskKey key;private final long expectedEnd;
        private final Path temporary;private final FileOutputStream file;private final DataOutputStream output;
        private long records,lastEnd;private boolean committed;
        private IndexRebuild(TransferTaskKey key,long end)throws IOException {this.key=key;expectedEnd=end;temporary=Files.createTempFile(root,"index-",".tmp");permissions(temporary,"rw-------");file=new FileOutputStream(temporary.toFile());output=new DataOutputStream(file);}
        public void append(long end,byte[] hash)throws IOException {if(committed||hash==null||hash.length!=32||end>expectedEnd||end<=records*TransferLimits.BLOCK_BYTES||end>(records+1)*TransferLimits.BLOCK_BYTES)throw new IOException("Invalid rebuilt block index");output.writeLong(end);output.write(hash);records++;lastEnd=end;}
        public void commit()throws IOException {if(committed||lastEnd!=expectedEnd)throw new IOException("Incomplete rebuilt block index");output.flush();file.getChannel().force(true);output.close();Files.move(temporary,entry(key,".blocks"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);committed=true;syncDirectory(root);}
        public void close()throws IOException {try{output.close();}finally{Files.deleteIfExists(temporary);}}
    }
    public PrefixResult verifyPrefix(TransferCheckpoint checkpoint,Path content)throws IOException {return verifyPrefix(checkpoint,content,()->false);}
    public PrefixResult verifyPrefix(TransferCheckpoint checkpoint,Path content,java.util.function.BooleanSupplier canceled)throws IOException {
        return verifyPrefix(checkpoint,content,canceled,bytes->{});
    }
    public PrefixResult verifyPrefix(TransferCheckpoint checkpoint,Path content,java.util.function.BooleanSupplier canceled,PrefixProgress progress)throws IOException {
        if(Files.isSymbolicLink(content)||!Files.isRegularFile(content,LinkOption.NOFOLLOW_LINKS)||Files.size(content)<checkpoint.durableOffset())throw new IOException("Checkpoint content missing/truncated");
        MessageDigest whole=sha(),prefix=sha();long offset=0;
        Path index=entry(checkpoint.key(),".blocks");
        if(checkpoint.durableOffset()==0)return new PrefixResult(whole,prefix.digest(),0);
        try(InputStream input=Files.newInputStream(content,LinkOption.NOFOLLOW_LINKS);DataInputStream blocks=new DataInputStream(Files.newInputStream(index,LinkOption.NOFOLLOW_LINKS))) {
            byte[] buffer=new byte[TransferLimits.DATA_BYTES];
            while(offset<checkpoint.durableOffset()) {
                long end=blocks.readLong(),expectedEnd=Math.min(offset+TransferLimits.BLOCK_BYTES,checkpoint.info().size);byte[] expected=new byte[32];blocks.readFully(expected);
                if(end!=expectedEnd||end>checkpoint.durableOffset())throw new IOException("Invalid checkpoint block boundary");MessageDigest block=sha();
                while(offset<end){if(canceled.getAsBoolean())throw new InterruptedIOException("Prefix verification canceled");int n=input.read(buffer,0,(int)Math.min(buffer.length,end-offset));if(n<1)throw new EOFException("Truncated saved prefix");whole.update(buffer,0,n);prefix.update(buffer,0,n);block.update(buffer,0,n);offset+=n;}
                if(!MessageDigest.isEqual(block.digest(),expected))throw new IOException("Saved prefix checksum mismatch");
                progress.checked(offset);
            }
        }
        return new PrefixResult(whole,prefix.digest(),offset);
    }
    public synchronized List<TransferCheckpoint> list()throws IOException {
        List<TransferCheckpoint> result=new ArrayList<>();
        try(var paths=Files.list(root)) {
            for(Path file:(Iterable<Path>)paths::iterator) {
                String name=file.getFileName().toString();if(!name.endsWith(".checkpoint"))continue;
                if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_CHECKPOINT)throw new IOException("Unsafe checkpoint entry");
                byte[] bytes=readCheckpoint(file);
                DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=MAGIC)throw new IOException("Invalid checkpoint");
                String local=in.readUTF(),remote=in.readUTF();int direction=in.readUnsignedByte();if(direction>=TransferTaskKey.Direction.values().length)throw new IOException("Invalid checkpoint direction");
                try {TransferTaskKey key=new TransferTaskKey(local,remote,TransferTaskKey.Direction.values()[direction],in.readUTF(),in.readUTF());if(!name.equals(key.fileName()+".checkpoint"))throw new IOException("Checkpoint filename mismatch");result.add(load(key).orElseThrow(()->new IOException("Checkpoint disappeared")));}
                catch(IllegalArgumentException error){throw new IOException("Invalid checkpoint identity",error);}
            }
        }return result;
    }
    private byte[] readCheckpoint(Path file)throws IOException {
        long length=Files.size(file);if(length<36||length>MAX_CHECKPOINT)throw new IOException("Invalid checkpoint length");byte[] bytes=new byte[(int)length];
        try(DataInputStream input=new DataInputStream(Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS))){input.readFully(bytes);if(input.read()!=-1)throw new IOException("Checkpoint grew while reading");}return bytes;
    }
    public synchronized void complete(TransferTaskKey key,long length,byte[] hash)throws IOException {
        TransferCheckpoint before=load(key).orElseThrow(()->new IOException("Unknown completed task"));if(length!=before.info().size||hash.length!=32)throw new IOException("Invalid completion");
        AttachmentInfo old=before.info(),info=AttachmentInfo.v2(old.id,old.name,old.mime,length,AttachmentInfo.hex(hash),old.time);
        checkpoint(new TransferCheckpoint(key,info,before.sourceReference(),length,length,length,TransferCheckpoint.State.COMPLETE,System.currentTimeMillis()));
    }
    public synchronized void cancel(TransferTaskKey key)throws IOException {
        TransferCheckpoint before=load(key).orElseThrow(()->new IOException("Unknown canceled task"));
        checkpoint(new TransferCheckpoint(key,before.info(),before.sourceReference(),0,0,0,TransferCheckpoint.State.CANCELED,System.currentTimeMillis()));Files.deleteIfExists(entry(key,".blocks"));syncDirectory(root);
    }
    static void syncDirectory(Path directory)throws IOException {
        // Windows does not expose an openable directory channel. File metadata is forced above.
        if(System.getProperty("os.name").startsWith("Windows"))return;
        try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
    }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void permissions(Path path,String permissions)throws IOException{if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(permissions));}
}
