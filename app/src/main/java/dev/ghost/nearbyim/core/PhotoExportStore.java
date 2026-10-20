package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Local publication journal; a gallery result never changes an authenticated saved receipt. */
public final class PhotoExportStore {
    public enum State {PENDING,COPIED,SAVED,MISSING,FAILED}
    public record Entry(State state,String uri){public Entry{Objects.requireNonNull(state);if(uri==null||uri.length()>8192||uri.indexOf('\0')>=0)throw new IllegalArgumentException("Invalid gallery location");}}
    private final Path root;
    public PhotoExportStore(Path root)throws IOException {PrivateMetadata.directory(root);this.root=root;}
    private static String peer(String peer)throws IOException {if(peer==null||!peer.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid gallery peer");return peer;}
    public static String key(String peer,AttachmentRecord record)throws IOException {
        peer(peer);if(record.info.hash==null||!record.info.mime.startsWith("image/")||!(record.state.equals("received")||record.state.equals("delivered")))throw new IOException("Photo not complete");
        return PrivateMetadata.key(peer+"\n"+record.info.id+"\n"+record.outgoing+"\n"+record.info.hash);
    }
    private Path file(String peer,AttachmentRecord record)throws IOException {return root.resolve(peer(peer)).resolve(key(peer,record)+".bin");}
    public synchronized Entry load(String peer,AttachmentRecord record)throws IOException {
        Path file=file(peer,record);byte[] bytes=PrivateMetadata.read(file,16384);if(bytes==null)return null;
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){if(input.readInt()!=0x57474531||!input.readUTF().equals(key(peer,record)))throw new IOException("Invalid gallery marker");Entry entry=new Entry(State.valueOf(input.readUTF()),input.readUTF());if(input.available()!=0)throw new IOException("Trailing gallery metadata");return entry;}catch(IllegalArgumentException bad){throw new IOException("Invalid gallery metadata",bad);}
    }
    public synchronized void save(String peer,AttachmentRecord record,Entry entry)throws IOException {
        Path file=file(peer,record);PrivateMetadata.directory(file.getParent());ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream output=new DataOutputStream(bytes);output.writeInt(0x57474531);output.writeUTF(key(peer,record));output.writeUTF(entry.state.name());output.writeUTF(entry.uri);PrivateMetadata.write(file,bytes.toByteArray(),16384);
    }
    public synchronized List<Entry> entries(String peer)throws IOException {
        Path directory=root.resolve(peer(peer));if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();if(Files.isSymbolicLink(directory))throw new IOException("Unsafe gallery markers");List<Entry> result=new ArrayList<>();
        try(var paths=Files.list(directory)){for(Path file:(Iterable<Path>)paths::iterator){if(file.getFileName().toString().startsWith(".journal-"))continue;byte[] bytes=PrivateMetadata.read(file,16384);if(bytes==null)continue;try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))){if(input.readInt()!=0x57474531)throw new IOException("Invalid gallery marker");String key=input.readUTF();if(!file.getFileName().toString().equals(key+".bin"))throw new IOException("Invalid gallery key");result.add(new Entry(State.valueOf(input.readUTF()),input.readUTF()));}}}catch(IllegalArgumentException bad){throw new IOException("Invalid gallery marker",bad);}return List.copyOf(result);
    }
    /** Only journal files are removed. Public gallery files belong to the user. */
    public synchronized void clear(String peer)throws IOException {
        Path directory=root.resolve(peer(peer));if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;if(Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe gallery markers");
        try(var files=Files.list(directory)){for(Path file:(Iterable<Path>)files::iterator){if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!(file.getFileName().toString().matches("[0-9a-f]{64}\\.bin")||file.getFileName().toString().matches("\\.journal-.*\\.tmp")))throw new IOException("Unsafe gallery marker");Files.delete(file);}}Files.delete(directory);
    }
}
