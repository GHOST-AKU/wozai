package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

public final class AttachmentResumeTests {
    private static int checks;
    private static final String ID="11111111-1111-1111-1111-111111111111",GEN="22222222-2222-2222-2222-222222222222";
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("resume-store-");
        try {
            TransferTaskKey key=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.RECEIVE,ID,GEN);
            TransferTaskKey other=new TransferTaskKey("a".repeat(64),"b".repeat(64),TransferTaskKey.Direction.SEND,ID,GEN);
            check(!key.fileName().equals(other.fileName()),"Directions share a task key");
            TransferCheckpointStore store=new TransferCheckpointStore(root.resolve("journal"));
            AttachmentInfo info=AttachmentInfo.v2(ID,"data.bin","application/octet-stream",TransferLimits.BLOCK_BYTES+3,null,1);
            TransferCheckpoint state=new TransferCheckpoint(key,info,"",TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferLimits.BLOCK_BYTES,TransferCheckpoint.State.PAUSED,System.currentTimeMillis());
            byte[] block=new byte[TransferLimits.BLOCK_BYTES];new Random(4).nextBytes(block);byte[] hash=MessageDigest.getInstance("SHA-256").digest(block);
            Path content=root.resolve("content.bin");Files.write(content,block);
            store.appendBlock(key,TransferLimits.BLOCK_BYTES,hash);store.checkpoint(state);
            TransferCheckpoint loaded=store.load(key).orElseThrow();check(loaded.durableOffset()==TransferLimits.BLOCK_BYTES,"Checkpoint offsets changed");
            check(store.load(other).isEmpty(),"Another direction recovered this task");
            check(Arrays.equals(store.verifyPrefix(loaded,content).sha256(),hash),"Verified prefix differs");
            try(RandomAccessFile file=new RandomAccessFile(content.toFile(),"rw")){file.seek(2);file.write(42);}
            rejects(()->store.verifyPrefix(loaded,content));
            Path journal=root.resolve("journal").resolve(key.fileName()+".checkpoint");byte[] bytes=Files.readAllBytes(journal);bytes[bytes.length-1]^=1;Files.write(journal,bytes);
            rejects(()->store.load(key));Files.delete(journal);store.checkpoint(state);
            store.cancel(key);check(store.load(key).orElseThrow().state()==TransferCheckpoint.State.CANCELED,"Cancellation tombstone missing");
            TransferCheckpoint revived=new TransferCheckpoint(key,info,"",0,0,0,TransferCheckpoint.State.ACTIVE,System.currentTimeMillis());
            rejects(()->store.checkpoint(revived));
            TransferStorageBudget budget=new TransferStorageBudget(root.resolve("budget"),100L*1024*1024);
            try(TransferStorageBudget.Reservation reservation=budget.reserve(key,80L*1024*1024,0)) {
                rejects(()->budget.reserve(other,30L*1024*1024,0));
                try(OutputStream output=Files.newOutputStream(root.resolve("budget/stored.part"))){for(int i=0;i<10;i++)output.write(block);}
                reservation.written(10L*1024*1024);
                check(budget.reservedBytes()==70L*1024*1024,"Written bytes remain reserved twice");
            }
            check(budget.reservedBytes()==0,"Storage reservation leaked");
            System.out.println("AttachmentResumeTests: "+checks+" checkpoint/integrity/budget checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected IOException");}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
}
