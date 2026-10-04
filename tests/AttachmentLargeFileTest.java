package dev.ghost.nearbyim.core;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
/** Run with -Xmx32m: the generated file exceeds the maximum Java heap. */
public final class AttachmentLargeFileTest {
    public static void main(String[] args)throws Exception {
        long size=48L*1024*1024;
        try(TransferTests.Pair p=new TransferTests.Pair()) {
            String id=p.a.transfers.offer(()->new InputStream(){
                long left=size;
                public int read(){if(left==0)return -1;left--;return 42;}
                public int read(byte[] bytes,int offset,int length){if(left==0)return -1;int n=(int)Math.min(left,length);java.util.Arrays.fill(bytes,offset,offset+n,(byte)42);left-=n;return n;}
            },"large.bin","application/octet-stream").get(5,TimeUnit.SECONDS);
            AttachmentRecord offer=p.b.waitFor("offered");p.b.transfers.accept(id).get(5,TimeUnit.SECONDS);p.b.waitFor("received");p.a.waitFor("delivered");
            Path file=AttachmentTransfer.file(p.b.root,offer.info);if(Files.size(file)!=size)throw new AssertionError("Large file truncated");
            try(InputStream input=Files.newInputStream(file)){byte[] bytes=new byte[32768];int n;while((n=input.read(bytes))!=-1)for(int i=0;i<n;i++)if(bytes[i]!=42)throw new AssertionError("Large file corrupt");}
            System.out.println("48 MiB signed TCP transfer verified with max heap "+Runtime.getRuntime().maxMemory()/1024/1024+" MiB");
        }
    }
}
