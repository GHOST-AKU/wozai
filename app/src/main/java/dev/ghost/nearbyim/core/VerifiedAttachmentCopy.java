package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.function.BooleanSupplier;

/** Export the completed original, with bounded memory and a final content check. */
public final class VerifiedAttachmentCopy {
    private VerifiedAttachmentCopy(){}
    public static void copy(Path source,OutputStream destination,AttachmentInfo info,BooleanSupplier canceled)throws IOException {
        if(info.hash==null||!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS)||Files.size(source)!=info.size)throw new IOException("Completed original unavailable");
        MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
        byte[] buffer=new byte[128*1024];long count=0;
        try(InputStream input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS)){
            int n;while((n=input.read(buffer))!=-1){if(canceled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Export canceled");if(n==0)continue;count+=n;if(count>info.size)throw new IOException("Completed original changed");digest.update(buffer,0,n);destination.write(buffer,0,n);}
        }
        if(canceled.getAsBoolean()||count!=info.size||!AttachmentInfo.hex(digest.digest()).equals(info.hash))throw new IOException("Completed original changed");
    }
}
