package dev.ghost.nearbyim.core;
/** Ephemeral UI measurements; never substitutes for a durable file receipt. */
public record TransferProgress(long bytesPerSecond,long remainingSeconds) {
    public static final TransferProgress UNKNOWN=new TransferProgress(0,-1);
    public TransferProgress {if(bytesPerSecond<0||remainingSeconds< -1)throw new IllegalArgumentException("Invalid transfer measurement");}
}
