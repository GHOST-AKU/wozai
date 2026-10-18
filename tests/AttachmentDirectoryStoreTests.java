package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class AttachmentDirectoryStoreTests {
    private static int checks;
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("receive-directory-");String peer=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"原件.txt","text/plain",4,"a".repeat(64),1);
        AttachmentRecord record=new AttachmentRecord(info,false,"received",4);
        String tree="content://documents/tree/receive",document=tree+"/document/original";
        try {
            AttachmentDirectoryStore store=new AttachmentDirectoryStore(root);
            check(store.load(peer,record)==null,"Unscheduled history must not become an automatic save job");
            store.save(peer,record,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.PENDING,tree,""));
            check(new AttachmentDirectoryStore(root).load(peer,record).state()==AttachmentDirectoryStore.State.PENDING,"Pending save was lost across restart");
            store.save(peer,record,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.COPIED,tree,document));
            check(store.retainedTrees().contains(tree),"Uncommitted copy lost its directory grant");
            store.save(peer,record,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.SAVED,tree,document));
            check(new AttachmentDirectoryStore(root).load(peer,record).documentUri().equals(document),"Completed save lost its public document");
            check(store.load(other,record)==null,"A different peer inherited another transfer's destination");
            refused(()->store.load(peer,new AttachmentRecord(info,false,"paused",2)));
            refused(()->store.load(peer,new AttachmentRecord(info,true,"delivered",4)));
            try {new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.SAVED,tree,"");throw new AssertionError("Saved file may not have an empty URI");}catch(IllegalArgumentException expected){checks++;}
            try {new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.PENDING,"file:///private","");throw new AssertionError("Private path accepted as a public directory");}catch(IllegalArgumentException expected){checks++;}
            store.save(other,record,new AttachmentDirectoryStore.Entry(AttachmentDirectoryStore.State.MISSING,tree,document));
            store.clear(peer);check(store.load(peer,record)==null&&store.load(other,record).state()==AttachmentDirectoryStore.State.MISSING,"Clearing a chat removed another peer's destination or retained the cleared marker");
            Path marker;try(var paths=Files.list(root.resolve(other))){marker=paths.findFirst().orElseThrow();}Files.write(marker,new byte[]{1,2});
            refused(store::retainedTrees);
            System.out.println("AttachmentDirectoryStoreTests: "+checks+" checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private interface Work{void run()throws Exception;}
    private static void refused(Work work)throws Exception {try{work.run();throw new AssertionError("Unsafe or corrupt receive-directory metadata accepted");}catch(IOException expected){checks++;}}
}
