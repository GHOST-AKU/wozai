package dev.ghost.nearbyim.core;

/** Bounded, payload-free view used by the route actor; no checkpoint I/O. */
public final class FileTransferActivity {
    public static final FileTransferActivity IDLE=new FileTransferActivity(false,false,false,0,0,0,0,0,0);
    public final boolean active,sending,busy;
    public final long remaining,rate,capacity,switchCost,activeNanos,stalledNanos;
    FileTransferActivity(boolean active,boolean sending,boolean busy,long remaining,long rate,long capacity,long cost,long age,long stalled){
        this.active=active;this.sending=sending;this.busy=busy;this.remaining=remaining;this.rate=rate;this.capacity=capacity;switchCost=cost;activeNanos=age;stalledNanos=stalled;
    }
}
