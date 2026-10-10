package dev.ghost.nearbyim.core;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLongArray;

/** Fixed-size timing counters. Never retains payloads, filenames, addresses or identities. */
public final class TransferDiagnostics {
    public enum Stage {
        SOURCE_OPEN, SOURCE_READ, HASH, BLOCK_INDEX, FILE_WRITE, CONTENT_FORCE,
        CHECKPOINT, HISTORY_SAVE, CREDIT_WAIT, RECEIVE_QUEUE, WRITER_QUEUE,
        ENCRYPT, SOCKET_WRITE, SOCKET_HEADER, SOCKET_READ, DECRYPT
    }
    private final AtomicLongArray counts=new AtomicLongArray(Stage.values().length),
        bytes=new AtomicLongArray(Stage.values().length), totals=new AtomicLongArray(Stage.values().length),
        maxima=new AtomicLongArray(Stage.values().length), active=new AtomicLongArray(Stage.values().length);
    /** A stage has one worker per owning pipeline; unrelated workers use different stages. */
    public long begin(Stage stage){long now=System.nanoTime();active.set(stage.ordinal(),now);return now;}
    public void end(Stage stage,long began,long byteCount){active.compareAndSet(stage.ordinal(),began,0);record(stage,System.nanoTime()-began,byteCount);}
    public void record(Stage stage,long nanos,long byteCount) {
        if(nanos<0||byteCount<0)throw new IllegalArgumentException("Invalid timing sample");
        int i=stage.ordinal();counts.incrementAndGet(i);bytes.addAndGet(i,byteCount);totals.addAndGet(i,nanos);
        long old;do{old=maxima.get(i);if(nanos<=old)break;}while(!maxima.compareAndSet(i,old,nanos));
    }
    /** Formatting happens only when requested, never in the DATA packet path. */
    public String snapshot() {
        StringBuilder report=new StringBuilder();long now=System.nanoTime();
        for(Stage stage:Stage.values()) {
            int i=stage.ordinal();long count=counts.get(i),began=active.get(i);if(count==0&&began==0)continue;
            report.append(String.format(Locale.ROOT,"%s: count=%d, bytes=%d, total_ms=%.3f, max_ms=%.3f, active_ms=%.3f%n",
                stage.name().toLowerCase(Locale.ROOT),count,bytes.get(i),totals.get(i)/1e6,maxima.get(i)/1e6,began==0?0:Math.max(0,now-began)/1e6));
        }
        return report.toString();
    }
}
