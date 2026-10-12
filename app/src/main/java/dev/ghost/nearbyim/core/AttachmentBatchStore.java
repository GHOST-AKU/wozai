package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Identity-scoped queue journal. Metadata only; no source file is kept open. */
final class AttachmentBatchStore {
    static final int LIMIT=2*1024*1024,MAX_ITEMS=256;
    private final Path file;
    final String local,remote;
    AttachmentBatchStore(Path root,String local,String remote)throws IOException {
        if(!local.matches("[0-9a-f]{64}")||!remote.matches("[0-9a-f]{64}"))throw new IOException("Invalid batch identities");
        this.local=local;this.remote=remote;PrivateMetadata.directory(root.resolve(".batch-v1"));
        file=root.resolve(".batch-v1").resolve(PrivateMetadata.key(local+"\n"+remote)+".bin");
    }
    record Saved(boolean paused,List<AttachmentBatchQueue.Item> items){}
    Saved read()throws IOException {return decode(PrivateMetadata.read(file,LIMIT),local,remote);}
    void write(boolean paused,List<AttachmentBatchQueue.Item> items)throws IOException {
        if(items.size()>MAX_ITEMS)throw new IOException("Batch history bound exceeded");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(0x57425131);out.writeUTF(local);out.writeUTF(remote);out.writeBoolean(paused);out.writeInt(items.size());
        for(var item:items){out.writeUTF(item.batchId());item.info().writeV2(out);var s=item.selection();out.writeUTF(s.reference());out.writeUTF(s.generation());out.writeLong(s.size());out.writeBoolean(s.exact());out.writeUTF(item.state());out.writeLong(item.transferred());}
        PrivateMetadata.write(file,bytes.toByteArray(),LIMIT);
    }
    private static Saved decode(byte[] bytes,String local,String remote)throws IOException {
        if(bytes==null)return new Saved(false,List.of());
        try(DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0x57425131)throw new IOException("Unknown batch journal");String a=in.readUTF(),b=in.readUTF();
            if(!a.matches("[0-9a-f]{64}")||!b.matches("[0-9a-f]{64}")||local!=null&&(!a.equals(local)||!b.equals(remote)))throw new IOException("Batch identity mismatch");
            boolean paused=in.readBoolean();int count=in.readInt();if(count<0||count>MAX_ITEMS)throw new IOException("Batch count exceeds bounds");
            List<AttachmentBatchQueue.Item> items=new ArrayList<>();Set<String> ids=new HashSet<>();
            for(int i=0;i<count;i++){
                String batch=in.readUTF();AttachmentInfo info=AttachmentInfo.readV2(in);String ref=in.readUTF(),generation=in.readUTF();long size=in.readLong();boolean exact=in.readBoolean();
                var selection=new AttachmentBatchQueue.Selection(ref,generation,info.name,info.mime,size,exact);String state=in.readUTF();long transferred=in.readLong();
                if(!batch.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")||!ids.add(info.id))throw new IOException("Duplicate batch task");
                new AttachmentRecord(info,true,state,transferred);items.add(new AttachmentBatchQueue.Item(batch,info,selection,state,transferred));
            }
            if(in.available()!=0)throw new IOException("Trailing batch metadata");return new Saved(paused,List.copyOf(items));
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid batch metadata",invalid);}
    }
    static List<String> references(Path root)throws IOException {
        Path directory=root.resolve(".batch-v1");if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe batch directory");
        List<String> refs=new ArrayList<>();int count=0;
        try(var files=Files.list(directory)){for(Path path:(Iterable<Path>)files::iterator){
            if(++count>128||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid batch entry");
            if(path.getFileName().toString().startsWith(".journal-")&&path.getFileName().toString().endsWith(".tmp"))continue;
            if(!path.getFileName().toString().matches("[0-9a-f]{64}\\.bin"))throw new IOException("Invalid batch filename");
            for(var item:decode(PrivateMetadata.read(path,LIMIT),null,null).items())if(!item.terminal())refs.add(item.selection().reference());
        }}return List.copyOf(refs);
    }
    static void clear(Path root)throws IOException {
        Path directory=root.resolve(".batch-v1");if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        references(root);try(var paths=Files.list(directory)){for(Path path:(Iterable<Path>)paths::iterator)Files.delete(path);}Files.delete(directory);
    }
    static List<AttachmentRecord> cancelPending(Path root)throws IOException {
        Path directory=root.resolve(".batch-v1");if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();
        references(root);List<AttachmentRecord> canceled=new ArrayList<>();
        try(var paths=Files.list(directory)){for(Path path:(Iterable<Path>)paths::iterator)if(path.getFileName().toString().endsWith(".bin"))
            for(var item:decode(PrivateMetadata.read(path,LIMIT),null,null).items())if(!item.terminal())canceled.add(new AttachmentRecord(item.info(),true,"canceled",item.transferred()));
        }clear(root);return canceled;
    }
}
