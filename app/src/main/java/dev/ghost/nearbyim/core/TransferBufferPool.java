package dev.ghost.nearbyim.core;

import java.io.IOException;
import java.util.*;

/** Allocated bytes include checked-out leases; exhaustion never allocates outside the budget. */
public final class TransferBufferPool implements AutoCloseable {
    private final long maximum;
    private final int bufferBytes;
    private final ArrayDeque<byte[]> idle=new ArrayDeque<>();
    private long allocated;
    private boolean closed;
    public TransferBufferPool(long maxRetainedBytes,int bufferBytes)throws IOException {
        if(bufferBytes<1||bufferBytes>TransferLimits.DATA_BYTES||maxRetainedBytes<bufferBytes||maxRetainedBytes>8L*1024*1024)throw new IOException("Invalid buffer budget");
        maximum=maxRetainedBytes;this.bufferBytes=bufferBytes;
    }
    public synchronized Lease acquire()throws IOException {
        if(closed)throw new IOException("Buffer pool closed");byte[] bytes=idle.pollFirst();
        if(bytes==null){if(bufferBytes>maximum-allocated)throw new IOException("Buffer pool exhausted");bytes=new byte[bufferBytes];allocated+=bufferBytes;}
        return new Lease(bytes);
    }
    public synchronized long retainedBytes(){return allocated;}
    public synchronized void close(){if(closed)return;closed=true;for(byte[] bytes:idle)Arrays.fill(bytes,(byte)0);allocated-=(long)idle.size()*bufferBytes;idle.clear();}
    public final class Lease implements AutoCloseable {
        private byte[] bytes;
        private Lease(byte[] bytes){this.bytes=bytes;}
        public byte[] bytes(){synchronized(TransferBufferPool.this){if(bytes==null)throw new IllegalStateException("Buffer lease returned");return bytes;}}
        public void close(){synchronized(TransferBufferPool.this){if(bytes==null)return;Arrays.fill(bytes,(byte)0);if(closed)allocated-=bufferBytes;else idle.addLast(bytes);bytes=null;}}
    }
}
