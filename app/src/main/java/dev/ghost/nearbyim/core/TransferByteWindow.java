package dev.ghost.nearbyim.core;

import java.io.IOException;

/** The advertised window starts at the receiver's cumulative written offset. */
public final class TransferByteWindow {
    private final long maxBytes;
    private long windowBytes,sent,written;
    public TransferByteWindow(long initialBytes,long maxBytes)throws IOException {
        if(maxBytes<1||maxBytes>8L*1024*1024||initialBytes<0||initialBytes>maxBytes)throw new IOException("Invalid transfer window");
        this.maxBytes=maxBytes;windowBytes=initialBytes;
    }
    public synchronized boolean tryReserve(long offset,int length)throws IOException {
        TransferLimits.validateRange(TransferLimits.MAX_FILE_BYTES,offset,length);
        if(offset!=sent||length<1)throw new IOException("Non-contiguous send reservation");
        if(length>windowBytes-(sent-written))return false;sent+=length;return true;
    }
    public synchronized void acknowledge(long writtenOffset,long availableBytes)throws IOException {
        if(writtenOffset<written||writtenOffset>sent||availableBytes<0||availableBytes>maxBytes)throw new IOException("Invalid cumulative credit");
        written=writtenOffset;windowBytes=availableBytes;
    }
    public synchronized long inFlightBytes(){return sent-written;}
    public synchronized long availableBytes(){return Math.max(0,windowBytes-(sent-written));}
    public synchronized long writtenOffset(){return written;}
}
