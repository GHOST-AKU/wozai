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
    public long bytes(Stage stage){return bytes.get(stage.ordinal());}
    public long nanos(Stage stage){return totals.get(stage.ordinal());}
    public boolean active(Stage... stages){for(Stage stage:stages)if(active.get(stage.ordinal())!=0)return true;return false;}
    /** Formatting happens only when requested, never in the DATA packet path. */
    public String snapshot() {
        StringBuilder report=new StringBuilder("counter_scope=owning_component_instance; reset=construction; totals=sum_of_samples_not_wall_time; overlapping_stages=not_additive\n"
            +"counter_bytes=source_read_includes_resume_hash_rereads; hash_includes_prefix_verification; wire_includes_control_probe_and_framing; queue_bytes=retained_memory_not_payload\n"
            +"counter_waits=credit_wait:backpressure,receive_queue:enqueue_to_actor,writer_queue:enqueue_to_writer,socket_header:network_wait_and_header_read; active_ms=unfinished_sample_not_transfer_duration\n");long now=System.nanoTime();
        for(Stage stage:Stage.values()) {
            int i=stage.ordinal();long count=counts.get(i),began=active.get(i);if(count==0&&began==0)continue;
            report.append(String.format(Locale.ROOT,"%s: count=%d, bytes=%d, total_ms=%.3f, max_ms=%.3f, active_ms=%.3f%n",
                stage.name().toLowerCase(Locale.ROOT),count,bytes.get(i),totals.get(i)/1e6,maxima.get(i)/1e6,began==0?0:Math.max(0,now-began)/1e6));
        }
        return report.toString();
    }
}
