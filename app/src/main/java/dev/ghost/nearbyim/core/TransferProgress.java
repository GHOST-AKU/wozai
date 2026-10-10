package dev.ghost.nearbyim.core;
/** Ephemeral UI measurements; never substitutes for a durable file receipt. */
public record TransferProgress(long bytesPerSecond,long remainingSeconds,long checkedBytes,long checkingTotalBytes) {
    public TransferProgress(long bytesPerSecond,long remainingSeconds){this(bytesPerSecond,remainingSeconds,0,-1);}
    public static final TransferProgress UNKNOWN=new TransferProgress(0,-1);
    public TransferProgress {if(bytesPerSecond<0||remainingSeconds< -1||checkedBytes<0||checkingTotalBytes< -1||checkingTotalBytes>TransferLimits.MAX_FILE_BYTES||checkingTotalBytes<0&&checkedBytes!=0||checkingTotalBytes>=0&&checkedBytes>checkingTotalBytes)throw new IllegalArgumentException("Invalid transfer measurement");}
    public boolean checking(){return checkingTotalBytes>=0;}
}
