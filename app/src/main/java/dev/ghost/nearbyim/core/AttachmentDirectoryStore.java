package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Durable automatic-save jobs. Public documents remain owned by the user. */
public final class AttachmentDirectoryStore {
    public enum State {PENDING,COPIED,SAVED,MISSING,FAILED}
    public record Entry(State state,String treeUri,String documentUri) {
        public Entry {
            Objects.requireNonNull(state);new AttachmentExportStore.Entry(treeUri,false,0);
            if(documentUri==null)throw new IllegalArgumentException("Missing document URI");
            if(!documentUri.isEmpty())new AttachmentExportStore.Entry(documentUri,false,0);
            if(documentUri.isEmpty()&&(state==State.COPIED||state==State.SAVED||state==State.MISSING))throw new IllegalArgumentException("Completed save has no document");
        }
    }
    private final Path root;
    public AttachmentDirectoryStore(Path root)throws IOException {PrivateMetadata.directory(root);this.root=root;}
    private static String peer(String peer)throws IOException {
        if(peer==null||!peer.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid receive-directory peer");return peer;
    }
    private static String key(String peer,AttachmentRecord record)throws IOException {
        if(record==null||record.outgoing)throw new IOException("Only completed received files can be saved automatically");return AttachmentExportStore.key(peer(peer),record);
    }
    private Path file(String peer,AttachmentRecord record)throws IOException {return root.resolve(peer(peer)).resolve(key(peer,record)+".bin");}
    public synchronized Entry load(String peer,AttachmentRecord record)throws IOException {
        byte[] bytes=PrivateMetadata.read(file(peer,record),32768);return bytes==null?null:decode(bytes,key(peer,record));
    }
    private static Entry decode(byte[] bytes,String expected)throws IOException {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(input.readInt()!=0x57444631||!input.readUTF().equals(expected))throw new IOException("Invalid receive-directory marker");
            Entry entry=new Entry(State.valueOf(input.readUTF()),input.readUTF(),input.readUTF());
            if(input.available()!=0)throw new IOException("Trailing receive-directory metadata");return entry;
        }catch(IllegalArgumentException invalid){throw new IOException("Invalid receive-directory metadata",invalid);}
    }
    public synchronized void save(String peer,AttachmentRecord record,Entry entry)throws IOException {
        Path file=file(peer,record);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream output=new DataOutputStream(bytes)){output.writeInt(0x57444631);output.writeUTF(key(peer,record));output.writeUTF(entry.state().name());output.writeUTF(entry.treeUri());output.writeUTF(entry.documentUri());}
        PrivateMetadata.write(file,bytes.toByteArray(),32768);
    }
    public synchronized Set<String> retainedTrees()throws IOException {
        Set<String> trees=new HashSet<>();
        try(var peers=Files.list(root)){for(Path directory:(Iterable<Path>)peers::iterator){
            peer(directory.getFileName().toString());if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe receive-directory markers");
            try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){
                String name=file.getFileName().toString();if(name.startsWith(".journal-"))continue;
                if(!name.matches("[0-9a-f]{64}\\.bin"))throw new IOException("Invalid receive-directory marker name");
                byte[] bytes=PrivateMetadata.read(file,32768);if(bytes!=null)trees.add(decode(bytes,name.substring(0,64)).treeUri());
            }}
        }}return Set.copyOf(trees);
    }
    /** Remove metadata only; never traverse the user's chosen directory. */
    public synchronized void clear(String peer)throws IOException {
        Path directory=root.resolve(peer(peer));if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe receive-directory markers");
        try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){
            String name=file.getFileName().toString();if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!(name.matches("[0-9a-f]{64}\\.bin")||name.matches("\\.journal-.*\\.tmp")))throw new IOException("Unsafe receive-directory marker");Files.delete(file);
        }}Files.delete(directory);
    }
}
