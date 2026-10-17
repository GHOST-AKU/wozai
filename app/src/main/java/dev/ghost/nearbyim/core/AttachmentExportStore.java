package dev.ghost.nearbyim.core;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/** Public document references only; clearing these markers never deletes user documents. */
public final class AttachmentExportStore {
    public record Entry(String uri,boolean persistentRead,long savedAt) {
        public Entry {
            if(uri==null||uri.length()>8192||uri.indexOf('\0')>=0||savedAt<0)throw new IllegalArgumentException("Invalid export location");
            URI parsed;try{parsed=URI.create(uri);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid export location",invalid);}
            if(!"content".equals(parsed.getScheme())||parsed.getRawAuthority()==null||parsed.getRawAuthority().isEmpty()||parsed.getRawUserInfo()!=null)throw new IllegalArgumentException("Export location must be a public content URI");
        }
    }
    private final Path root;
    public AttachmentExportStore(Path root)throws IOException {PrivateMetadata.directory(root);this.root=root;}
    private static String peer(String peer)throws IOException {
        if(peer==null||!peer.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid export peer");return peer;
    }
    public static void requireComplete(AttachmentRecord record)throws IOException {
        if(record==null||record.info.hash==null||record.transferred!=record.info.size||!(record.outgoing?record.state.equals("delivered"):record.state.equals("received")))throw new IOException("Attachment is not complete");
    }
    public static String key(String peer,AttachmentRecord record)throws IOException {
        peer(peer);requireComplete(record);return PrivateMetadata.key(peer+"\n"+record.info.id+"\n"+record.outgoing+"\n"+record.info.hash);
    }
    private Path file(String peer,AttachmentRecord record)throws IOException {return root.resolve(peer(peer)).resolve(key(peer,record)+".bin");}
    public synchronized Entry load(String peer,AttachmentRecord record)throws IOException {
        byte[] bytes=PrivateMetadata.read(file(peer,record),16384);return bytes==null?null:decode(bytes,key(peer,record));
    }
    private static Entry decode(byte[] bytes,String expected)throws IOException {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(input.readInt()!=0x57414531||!input.readUTF().equals(expected))throw new IOException("Invalid export marker");
            String uri=input.readUTF();int permission=input.readUnsignedByte();if(permission>1)throw new IOException("Invalid export grant marker");
            Entry entry=new Entry(uri,permission==1,input.readLong());if(input.available()!=0)throw new IOException("Trailing export metadata");return entry;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid export metadata",invalid);}
    }
    public synchronized void save(String peer,AttachmentRecord record,Entry entry)throws IOException {
        Path file=file(peer,record);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream output=new DataOutputStream(bytes)){output.writeInt(0x57414531);output.writeUTF(key(peer,record));output.writeUTF(entry.uri());output.writeBoolean(entry.persistentRead());output.writeLong(entry.savedAt());}
        PrivateMetadata.write(file,bytes.toByteArray(),16384);
    }
    public synchronized void forget(String peer,AttachmentRecord record)throws IOException {Path marker=file(peer,record);if(Files.deleteIfExists(marker))TransferCheckpointStore.syncDirectory(marker.getParent());}
    /** Fail closed on corrupt markers so unrelated cleanup cannot revoke a still-used grant. */
    public synchronized Set<String> retainedUris()throws IOException {
        Set<String> uris=new HashSet<>();
        try(var peers=Files.list(root)){for(Path directory:(Iterable<Path>)peers::iterator){
            peer(directory.getFileName().toString());if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe export markers");
            try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){
                String name=file.getFileName().toString();if(name.startsWith(".journal-"))continue;
                if(!name.matches("[0-9a-f]{64}\\.bin"))throw new IOException("Invalid export marker name");
                byte[] bytes=PrivateMetadata.read(file,16384);if(bytes!=null)uris.add(decode(bytes,name.substring(0,64)).uri());
            }}
        }}return Set.copyOf(uris);
    }
    public synchronized void clear(String peer)throws IOException {
        Path directory=root.resolve(peer(peer));if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe export markers");
        try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){
            String name=file.getFileName().toString();if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!(name.matches("[0-9a-f]{64}\\.bin")||name.matches("\\.journal-.*\\.tmp")))throw new IOException("Unsafe export marker");Files.delete(file);
        }}Files.delete(directory);
    }
}
