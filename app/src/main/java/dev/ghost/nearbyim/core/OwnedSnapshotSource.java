package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
/** A bounded private compatibility copy, retained on pause and deleted at a terminal state. */
public final class OwnedSnapshotSource implements AttachmentSource {
    private final FileAttachmentSource source;
    private final Path directory;
    public OwnedSnapshotSource(FileAttachmentSource source,Path directory)throws IOException {
        this.source=source;this.directory=directory.toAbsolutePath().normalize();
        if(Files.isSymbolicLink(this.directory)||!source.path().getParent().equals(this.directory))throw new IOException("Unsafe snapshot ownership");
    }
    public static OwnedSnapshotSource restore(Path directory,String reference,String generation)throws IOException {
        if(!reference.startsWith("snapshot:\n"))throw new IOException("Not an owned snapshot");
        try {Path file=Paths.get(URI.create(reference.substring(10).split("\n",2)[0]));FileAttachmentSource source=new FileAttachmentSource(file,generation);
            try{OwnedSnapshotSource owned=new OwnedSnapshotSource(source,directory);if(!owned.persistentReference().equals(reference))throw new IOException("Snapshot changed");return owned;}catch(IOException|RuntimeException error){source.close();throw error;}
        }catch(IllegalArgumentException error){throw new IOException("Invalid snapshot reference",error);}
    }
    public long size(){return source.size();}public String generation(){return source.generation();}public boolean seekable(){return true;}
    public InputStream open(long offset)throws IOException{return source.open(offset);}public void verifyUnchanged()throws IOException{source.verifyUnchanged();}
    public String persistentReference(){return "snapshot:\n"+source.persistentReference();}
    public Path path(){return source.path();}
    public void close()throws IOException{source.close();}
    public void discard()throws IOException{close();discardSaved(directory,persistentReference());}
    public static void discardSaved(Path directory,String reference)throws IOException {
        try{Path parent=directory.toAbsolutePath().normalize(),file=Paths.get(URI.create(reference.substring(10).split("\n",2)[0])).toAbsolutePath().normalize();
            if(!reference.startsWith("snapshot:\n")||!file.getParent().equals(parent)||Files.isSymbolicLink(parent)||Files.isSymbolicLink(file))throw new IOException("Unsafe snapshot ownership");Files.deleteIfExists(file);
        }catch(IllegalArgumentException|IndexOutOfBoundsException error){throw new IOException("Invalid snapshot reference",error);}
    }
}
