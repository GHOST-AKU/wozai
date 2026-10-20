package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.*;

/** Compatibility for pipes/unstable providers; successful files remain task-owned. */
public final class AttachmentSourceSnapshot {
    public interface Progress {void written(long bytes)throws IOException;}
    private AttachmentSourceSnapshot(){}
    public static FileAttachmentSource create(AttachmentSource source,Path privateRoot,long quotaRemaining) throws IOException {
        return create(source,privateRoot,quotaRemaining,bytes->{});
    }
    public static FileAttachmentSource create(AttachmentSource source,Path privateRoot,long quotaRemaining,Progress progress) throws IOException {
        long declared=source.size();
        if(quotaRemaining<0||declared< -1||declared>TransferLimits.MAX_FILE_BYTES||declared>quotaRemaining)throw new IOException("Source snapshot exceeds available quota");
        if(Files.isSymbolicLink(privateRoot)||Files.exists(privateRoot,LinkOption.NOFOLLOW_LINKS)&&!Files.isDirectory(privateRoot,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe snapshot directory");
        Files.createDirectories(privateRoot);privatePermissions(privateRoot,"rwx------");
        Path file=Files.createTempFile(privateRoot,"source-",".snapshot");boolean complete=false;
        try {
            privatePermissions(file,"rw-------");source.verifyUnchanged();
            long maximum=Math.min(TransferLimits.MAX_FILE_BYTES,quotaRemaining),size=0;int emptyReads=0;
            try(InputStream input=source.open(0);FileOutputStream output=new FileOutputStream(file.toFile())) {
                byte[] buffer=new byte[TransferLimits.DATA_BYTES];int n;
                while((n=input.read(buffer))!=-1) {
                    if(n==0){if(++emptyReads>32)throw new IOException("Source repeatedly returned no data");continue;}emptyReads=0;
                    if(n>maximum-size||declared>=0&&n>declared-size)throw new IOException("Source snapshot exceeds size or quota");
                    output.write(buffer,0,n);size+=n;progress.written(size);
                }
                if(declared>=0&&size!=declared)throw new EOFException("Source snapshot truncated");
                source.verifyUnchanged();output.getChannel().force(true);
            }
            FileAttachmentSource result=new FileAttachmentSource(file,source.generation());complete=true;return result;
        } finally {if(!complete)Files.deleteIfExists(file);}
    }
    /** The temporary reservation includes the original byte stream and both active directions. */
    public static OwnedSnapshotSource owned(AttachmentSource source,Path privateRoot,TransferStorageBudget budget,TransferTaskKey key)throws IOException {
        FileAttachmentSource copy=null;boolean handedOff=false;
        try {
            long maximum=budget.snapshotAllowance(source.size());
            try(TransferStorageBudget.Reservation reservation=budget.reserve(key,maximum,0)) {
                copy=create(source,privateRoot,maximum,reservation::written);source.close();source.discard();
                OwnedSnapshotSource owned=new OwnedSnapshotSource(copy,privateRoot);handedOff=true;return owned;
            }
        }finally{try{source.close();if(!handedOff)source.discard();}finally{if(!handedOff&&copy!=null){copy.close();Files.deleteIfExists(copy.path());}}}
    }
    private static void privatePermissions(Path path,String permissions) throws IOException {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(permissions));
    }
}
