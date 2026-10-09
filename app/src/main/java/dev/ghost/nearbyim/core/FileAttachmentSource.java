package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Stable open handle, positional reads, and observable source-change checks. */
public final class FileAttachmentSource implements AttachmentSource {
    private final Path file;
    private final FileChannel channel;
    private final BasicFileAttributes original;
    private final FileTime changeTime;
    private final String generation;

    public FileAttachmentSource(Path file) throws IOException {this(file,UUID.randomUUID().toString());}
    public FileAttachmentSource(Path file,String generation) throws IOException {
        if(generation==null||!generation.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))throw new IOException("Invalid source generation");
        this.file=file.toAbsolutePath().normalize();this.generation=generation;
        original=Files.readAttributes(this.file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!original.isRegularFile()||original.isSymbolicLink())throw new IOException("Source is not a regular file");
        TransferLimits.validateRange(original.size(),0,0);changeTime=changeTime(this.file);
        channel=FileChannel.open(this.file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);
        try {verifyUnchanged();if(channel.size()!=original.size())throw new IOException("Source changed while opening");}
        catch(IOException|RuntimeException error){channel.close();throw error;}
    }
    public long size(){return original.size();}
    public String generation(){return generation;}
    public boolean seekable(){return true;}
    public Path path(){return file;}
    public void verifyUnchanged() throws IOException {
        BasicFileAttributes now=Files.readAttributes(file,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!now.isRegularFile()||now.isSymbolicLink()||now.size()!=original.size()||!Objects.equals(now.fileKey(),original.fileKey())
                ||!now.lastModifiedTime().equals(original.lastModifiedTime())||changeTime!=null&&!changeTime.equals(changeTime(file)))
            throw new IOException("Attachment source changed");
    }
    private static FileTime changeTime(Path path) throws IOException {
        try{return (FileTime)Files.getAttribute(path,"unix:ctime",LinkOption.NOFOLLOW_LINKS);}
        catch(UnsupportedOperationException|IllegalArgumentException unavailable){return null;}
    }
    public InputStream open(long offset) throws IOException {
        TransferLimits.validateRange(size(),offset,0);if(!channel.isOpen())throw new IOException("Attachment source closed");verifyUnchanged();
        return new InputStream(){
            private long position=offset;
            private boolean closed;
            public int read() throws IOException {byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
            public int read(byte[] bytes,int start,int length) throws IOException {
                if(start<0||length<0||start>bytes.length-length)throw new IndexOutOfBoundsException();
                if(closed||!channel.isOpen())throw new IOException("Attachment source closed");
                if(length==0)return 0;if(position==size())return -1;
                int n=channel.read(ByteBuffer.wrap(bytes,start,(int)Math.min(length,size()-position)),position);
                if(n<0)throw new EOFException("Attachment source truncated");position+=n;return n;
            }
            public void close(){closed=true;}
        };
    }
    public void close() throws IOException {channel.close();}
}
