package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Small private journals. Uses attribute views supported by Android, not getFileStore. */
public final class PrivateMetadata {
    private PrivateMetadata(){}
    public static void directory(Path path)throws IOException {
        Path absolute=path.toAbsolutePath().normalize();
        for(Path p=absolute;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw new IOException("Unsafe metadata directory");
        Files.createDirectories(path);permissions(path,true);
    }
    public static void permissions(Path path,boolean directory)throws IOException {
        if(Files.getFileAttributeView(path,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS)!=null)
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
    public static byte[] read(Path file,int limit)throws IOException {
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||Files.size(file)>limit||Files.size(file)<32)throw new IOException("Invalid private journal");
        byte[] bytes;try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(n==0)continue;if(n>limit-out.size())throw new IOException("Private journal grew beyond bounds");out.write(buffer,0,n);}bytes=out.toByteArray();
        }
        if(bytes.length<32||bytes.length>limit)throw new IOException("Invalid private journal length");
        byte[] data=Arrays.copyOf(bytes,bytes.length-32),expected=Arrays.copyOfRange(bytes,bytes.length-32,bytes.length);
        if(!MessageDigest.isEqual(hash(data),expected))throw new IOException("Private journal checksum mismatch");return data;
    }
    public static void write(Path file,byte[] data,int limit)throws IOException {
        write(file,data,limit,()->false);
    }
    /** Optional cancellation stops before atomic replacement; the existing writer is unguarded. */
    public static void write(Path file,byte[] data,int limit,BooleanSupplier canceled)throws IOException {
        Objects.requireNonNull(canceled);checkCanceled(canceled);
        if(data.length+32>limit||Files.isSymbolicLink(file))throw new IOException("Private journal exceeds bounds");
        directory(file.getParent());Path temporary=Files.createTempFile(file.getParent(),".journal-",".tmp");
        try {
            permissions(temporary,false);
            try(FileOutputStream out=new FileOutputStream(temporary.toFile())){out.write(data);out.write(hash(data));checkCanceled(canceled);out.getChannel().force(true);}
            checkCanceled(canceled);
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);TransferCheckpointStore.syncDirectory(file.getParent());
        }finally{Files.deleteIfExists(temporary);}
    }
    private static void checkCanceled(BooleanSupplier canceled)throws InterruptedIOException {if(canceled.getAsBoolean())throw new InterruptedIOException("Private journal commit canceled");}
    public static String key(String text){return AttachmentInfo.hex(hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static byte[] hash(byte[] data){try{return MessageDigest.getInstance("SHA-256").digest(data);}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
}
