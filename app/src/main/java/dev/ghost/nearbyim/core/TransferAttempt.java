package dev.ghost.nearbyim.core;

import java.util.*;
import java.util.function.LongSupplier;

/** One local V2 attempt; DATA accounting excludes restored prefixes and control records. */
public final class TransferAttempt {
    private final String id=UUID.randomUUID().toString(),transfer,role;
    private final LongSupplier clock;
    private final long started,wall;
    private long changed,payload,prefix,confirmed,windowBytes,peak;
    private String phase="offered";
    private final Map<String,Long> phases=new LinkedHashMap<>();
    private final long[] buckets=new long[10];
    private long bucketTick=Long.MIN_VALUE;
    private String completed;
    private boolean verified;
    public synchronized void hashVerified(){verified=true;}
    public TransferAttempt(String transfer,boolean outgoing){this(transfer,outgoing,System::nanoTime,System.currentTimeMillis());}
    TransferAttempt(String transfer,boolean outgoing,LongSupplier clock,long wall){
        this.transfer=transfer;role=outgoing?"send":"receive";this.clock=clock;this.wall=wall;started=changed=clock.getAsLong();
    }
    public synchronized void phase(String next){if(completed!=null||phase.equals(next))return;long now=clock.getAsLong();phases.merge(phase,Math.max(0,now-changed),Long::sum);phase=next;changed=now;}
    public synchronized void data(long offset,long bytes){
        if(completed!=null||bytes<0||offset<0)throw new IllegalArgumentException("Invalid attempt DATA sample");
        if(payload==0)prefix=offset;
        payload+=bytes;
    }
    public synchronized void progress(long offset){
        long advanced=Math.max(0,Math.min(payload,offset-prefix)),bytes=Math.max(0,advanced-confirmed);confirmed=Math.max(confirmed,advanced);
        long now=clock.getAsLong(),tick=(now-started)/100_000_000L;
        if(bucketTick==Long.MIN_VALUE||tick-bucketTick>=10)Arrays.fill(buckets,0);
        else for(long i=bucketTick+1;i<=tick;i++)buckets[(int)(i%10)]=0;
        bucketTick=tick;buckets[(int)(tick%10)]+=bytes;
        windowBytes=0;for(long value:buckets)windowBytes+=value;
        // Report a peak only after a full second, not a burst divided by a few microseconds.
        if(now-started>=1_000_000_000L)peak=Math.max(peak,windowBytes);
    }
    public synchronized String snapshot(String state,long total,long written,long durable,String hash,boolean terminal){
        if(completed!=null)return completed;
        progress(written);phase(state);long now=clock.getAsLong(),elapsed=Math.max(0,now-started);
        Map<String,Long> times=new LinkedHashMap<>(phases);times.merge(phase,Math.max(0,now-changed),Long::sum);
        boolean success=state.equals("delivered")||state.equals("received");
        long restored=success?Math.max(prefix,total-payload):prefix;
        StringBuilder json=new StringBuilder("{");
        field(json,"schema",1);field(json,"transfer_id",transfer);field(json,"attempt_id",id);field(json,"role",role);
        field(json,"start_unix_ms",wall);field(json,"end_unix_ms",terminal?wall+elapsed/1_000_000L:null);
        field(json,"elapsed_ms",elapsed/1e6);field(json,"state",state);field(json,"file_bytes",total);
        field(json,"attempt_data_bytes",payload);field(json,"confirmed_payload_bytes",confirmed);field(json,"resumed_prefix_bytes",restored);field(json,"written_offset",written);field(json,"durable_offset",durable);
        field(json,"average_mib_s",elapsed==0?0:confirmed*1e9/elapsed/1048576.0);
        field(json,"peak_1s_mib_s",peak/1048576.0);field(json,"peak_window", "10x100ms_ack_or_write_buckets");
        field(json,"sha256",hash);field(json,"peer_complete_received",state.equals("delivered"));
        field(json,"local_hash_verified",verified);field(json,"terminal",terminal);
        json.append("\"phase_ms\":{");boolean first=true;
        for(var entry:times.entrySet()){if(!first)json.append(',');first=false;json.append(quote(entry.getKey())).append(':').append(entry.getValue()/1e6);}
        json.append("}}");String value=json.toString();if(terminal)completed=value;return value;
    }
    private static void field(StringBuilder out,String key,Object value){out.append(quote(key)).append(':').append(value==null?"null":value instanceof String?quote((String)value):value.toString()).append(',');}
    private static String quote(String value){StringBuilder out=new StringBuilder("\"");for(char c:value.toCharArray()){if(c=='"'||c=='\\')out.append('\\').append(c);else if(c<32)out.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else out.append(c);}return out.append('"').toString();}
}
