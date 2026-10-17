package dev.ghost.nearbyim.core;

/** Durable is a checked prefix, never inferred from a file's apparent length. */
public record TransferCheckpoint(TransferTaskKey key,AttachmentInfo info,String sourceReference,long writtenOffset,long verifiedOffset,long durableOffset,State state,long updatedMillis) {
    public enum State { ACTIVE, PAUSED, COMPLETE, CANCELED }
    public TransferCheckpoint {
        if(key==null||info==null||info.version!=2||!key.transferId().equals(info.id)||sourceReference==null||sourceReference.length()>8192||state==null||updatedMillis<0
                ||durableOffset<0||verifiedOffset<durableOffset||writtenOffset<verifiedOffset||writtenOffset>info.size
                ||durableOffset%TransferLimits.BLOCK_BYTES!=0&&durableOffset!=info.size||verifiedOffset%TransferLimits.BLOCK_BYTES!=0&&verifiedOffset!=info.size
                ||state==State.COMPLETE&&(info.hash==null||durableOffset!=info.size||verifiedOffset!=info.size||writtenOffset!=info.size))throw new IllegalArgumentException("Invalid transfer checkpoint");
    }
    public String blockIndexPath(){return key.fileName()+".blocks";}
}
