package dev.ghost.nearbyim.core;

import java.nio.file.*;
import java.io.*;
import java.util.*;

/** Saved public destinations are durable metadata, independent of transfer receipts. */
public final class AttachmentExportStoreTests {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("attachment-export-");String peer=UUID.randomUUID().toString();
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"report.txt","text/plain",3,"a".repeat(64),1);
        AttachmentRecord received=new AttachmentRecord(info,false,"received",3),sent=new AttachmentRecord(info,true,"delivered",3);
        try {
            AttachmentExportStore store=new AttachmentExportStore(root.resolve("markers"));
            AttachmentExportStore.Entry entry=new AttachmentExportStore.Entry("content://documents/document/report",true,5);
            store.save(peer,received,entry);
            check(new AttachmentExportStore(root.resolve("markers")).load(peer,received).equals(entry),"Saved location did not survive restart");
            check(store.load(UUID.randomUUID().toString(),received)==null,"Destination crossed peer scope");
            check(store.load(peer,sent)==null,"Destination crossed attachment direction");
            AttachmentInfo changed=AttachmentInfo.v2(info.id,info.name,info.mime,info.size,"b".repeat(64),1);
            check(store.load(peer,new AttachmentRecord(changed,false,"received",3))==null,"Destination crossed final digest");
            check(store.retainedUris().equals(Set.of(entry.uri())),"Saved read grant not retained");
            expectFailure(()->store.save(peer,new AttachmentRecord(info,false,"paused",2),entry));
            expectFailure(()->store.save(peer,new AttachmentRecord(info,true,"received",3),entry));
            expectFailure(()->new AttachmentExportStore.Entry("file:///data/private",false,5));
            expectFailure(()->new AttachmentExportStore.Entry("https://example.com/report",false,5));
            AttachmentExportStore.Entry ephemeral=new AttachmentExportStore.Entry("content://documents/document/second",false,6);
            store.save(peer,received,ephemeral);check(store.load(peer,received).equals(ephemeral),"Latest manual export did not replace previous destination");
            store.forget(peer,received);check(store.load(peer,received)==null,"Failed overwrite remained an openable saved location");store.save(peer,received,ephemeral);
            Path userFile=root.resolve("user-document");Files.write(userFile,new byte[]{1,2,3});
            try(var paths=Files.walk(root.resolve("markers"))){Path marker=paths.filter(Files::isRegularFile).findFirst().orElseThrow();byte[] bytes=Files.readAllBytes(marker);bytes[bytes.length-1]^=1;Files.write(marker,bytes);expectFailure(()->store.load(peer,received));expectFailure(store::retainedUris);}
            store.clear(peer);check(Files.exists(userFile)&&store.retainedUris().isEmpty(),"Clear deleted a public document or retained obsolete grant references");
            System.out.println("AttachmentExportStoreTests: durable public URIs, complete provenance, peer/direction/hash scope, corruption and ownership passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Checked {void run()throws Exception;}
    private static void expectFailure(Checked work)throws Exception {boolean failed=false;try{work.run();}catch(IOException|IllegalArgumentException expected){failed=true;}check(failed,"Unsafe or incomplete export metadata accepted");}
}
