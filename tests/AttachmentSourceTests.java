package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public final class AttachmentSourceTests {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("attachment-source-");
        try {
            long maximum=10_737_418_240L;
            check(TransferLimits.MAX_FILE_BYTES==maximum,"Wrong 10 GiB limit");
            TransferLimits.validateRange(0,0,0);TransferLimits.validateRange(maximum,maximum-1,1);
            for(long[] range:new long[][]{{-1,0,0},{maximum+1,0,0},{maximum,-1,0},{maximum,maximum,-1},{maximum,maximum,1},{maximum,Long.MAX_VALUE,1}})
                rejects(()->TransferLimits.validateRange(range[0],range[1],(int)range[2]));
            Path sparse=root.resolve("sparse.bin");
            try(RandomAccessFile file=new RandomAccessFile(sparse.toFile(),"rw")) {
                file.setLength(maximum);file.seek((1L<<32)+17);file.write(73);file.seek(maximum-1);file.write(92);
            }
            try(FileAttachmentSource source=new FileAttachmentSource(sparse)) {
                check(source.size()==maximum&&source.seekable(),"Sparse size was narrowed");
                try(InputStream input=source.open((1L<<32)+17)){check(input.read()==73,"Offset above 4 GiB narrowed");}
                try(InputStream input=source.open(maximum-1)){check(input.read()==92&&input.read()==-1,"End bound incorrect");}
                try(InputStream input=source.open(1L<<31)){check(input.read()==0,"Offset above 2 GiB narrowed");}
                rejects(()->source.open(-1));rejects(()->source.open(maximum+1));source.verifyUnchanged();
            }
            try(RandomAccessFile file=new RandomAccessFile(sparse.toFile(),"rw")){file.setLength(maximum+1);}
            rejects(()->new FileAttachmentSource(sparse));Files.delete(sparse);
            Path local=root.resolve("local.bin");Files.write(local,new byte[]{1,2,3,4});
            FileAttachmentSource closed=new FileAttachmentSource(local);InputStream view=closed.open(0);closed.close();rejects(view::read);rejects(()->closed.open(0));view.close();
            try(FileAttachmentSource source=new FileAttachmentSource(local)) {
                FileTime timestamp=Files.getLastModifiedTime(local);Files.write(local,new byte[]{4,3,2,1});Files.setLastModifiedTime(local,FileTime.fromMillis(timestamp.toMillis()+2000));
                rejects(source::verifyUnchanged);
            }
            try(FileAttachmentSource source=new FileAttachmentSource(local)) {Files.write(local,new byte[]{1});rejects(source::verifyUnchanged);}
            String generation=UUID.randomUUID().toString();
            AttachmentSource pipe=pipe(new byte[]{8,9,10},-1,generation);
            try(FileAttachmentSource snapshot=AttachmentSourceSnapshot.create(pipe,root.resolve("snapshots"),3)) {
                check(snapshot.size()==3&&snapshot.generation().equals(generation),"Snapshot lost source generation");
                try(InputStream input=snapshot.open(1)){check(Arrays.equals(input.readAllBytes(),new byte[]{9,10}),"Snapshot bytes changed");}
            }
            Path failed=root.resolve("failed");
            rejects(()->AttachmentSourceSnapshot.create(pipe(new byte[4],-1,generation),failed,3));
            try(var files=Files.list(failed)){check(files.findAny().isEmpty(),"Failed snapshot leaked content");}
            rejects(()->AttachmentSourceSnapshot.create(pipe(new byte[2],3,generation),failed,100));
            rejects(()->AttachmentSourceSnapshot.create(pipe(new byte[4],3,generation),failed,100));
            System.out.println("AttachmentSourceTests: "+checks+" checks passed (large offsets use sparse files; not 10 GiB physical acceptance)");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static AttachmentSource pipe(byte[] bytes,long size,String generation){return new AttachmentSource(){
        public long size(){return size;}public String generation(){return generation;}public boolean seekable(){return false;}
        public InputStream open(long offset)throws IOException {if(offset!=0)throw new IOException("Pipe cannot seek");return new ByteArrayInputStream(bytes);}
        public void verifyUnchanged(){}public void close(){}
    };}
    private interface Action{void run()throws Exception;}
    private static void rejects(Action action)throws Exception{try{action.run();}catch(IOException expected){checks++;return;}throw new AssertionError("Expected IOException");}
    private static void check(boolean valid,String message){if(!valid)throw new AssertionError(message);checks++;}
}
