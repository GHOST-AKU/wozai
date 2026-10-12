package dev.ghost.nearbyim.core;
import java.nio.file.*;
import java.util.*;

/** Export markers are separate from transfer receipts and never own exported user files. */
public final class PhotoExportStoreTests {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("photo-export-");String peer=UUID.randomUUID().toString();AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"photo.jpg","image/jpeg",3,"a".repeat(64),1);AttachmentRecord record=new AttachmentRecord(info,false,"received",3);
        try{
            PhotoExportStore store=new PhotoExportStore(root.resolve("markers"));Path userFile=root.resolve("gallery.jpg");Files.write(userFile,new byte[]{1,2,3});
            store.save(peer,record,new PhotoExportStore.Entry(PhotoExportStore.State.COPIED,userFile.toUri().toString()));
            if(store.load(peer,record).state()!=PhotoExportStore.State.COPIED)throw new AssertionError("Durable pre-publication state lost");
            if(store.load(UUID.randomUUID().toString(),record)!=null)throw new AssertionError("Gallery status leaked across peers");
            try(var entries=Files.walk(root.resolve("markers"))){Path file=entries.filter(Files::isRegularFile).findFirst().orElseThrow();byte[] bytes=Files.readAllBytes(file);bytes[bytes.length-1]^=1;Files.write(file,bytes);boolean rejected=false;try{store.load(peer,record);}catch(java.io.IOException expected){rejected=true;}if(!rejected)throw new AssertionError("Corrupt export journal accepted");}
            store.clear(peer);if(!Files.exists(userFile)||store.load(peer,record)!=null)throw new AssertionError("Marker clearing deleted user gallery image");
            System.out.println("PhotoExportStoreTests: durable copied marker, peer scope, corruption and user-file ownership passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
