package dev.ghost.nearbyim.core;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Probe traffic has a fixed budget and never enters a user file pipeline. */
public final class FileLinkProbeTests {
    public static void main(String[] args)throws Exception{
        ScheduledThreadPoolExecutor timer=new ScheduledThreadPoolExecutor(1);timer.setRemoveOnCancelPolicy(true);
        try{
            String nonce=UUID.randomUUID().toString(),generation="a".repeat(64);FileLinkProbe[] pair=new FileLinkProbe[2];
            long[] bytes=new long[4];CompletableFuture<FileRouteQualityPolicy.Sample[]> done=new CompletableFuture<>();
            AtomicInteger failures=new AtomicInteger();
            for(int i=0;i<2;i++){final int local=i;pair[i]=new FileLinkProbe(new FileLinkProbe.Host(){
                public boolean send(boolean lan,FileLinkPacket packet,BooleanSupplier current){timer.execute(()->{if(current.getAsBoolean()){if(packet.kind==FileLinkPacket.Kind.PROBE_DATA)bytes[packet.number]+=packet.data.length;pair[1-local].receive(packet,lan);}});return true;}
                public ScheduledExecutorService timer(){return timer;}
                public void done(FileRouteQualityPolicy.Sample[] samples){if(local==0)done.complete(samples);}
                public void failed(java.io.IOException error){failures.incrementAndGet();done.completeExceptionally(error);}
            },nonce,generation,i==0);}
            timer.submit(()->{pair[1].start();pair[0].start();}).get();
            FileRouteQualityPolicy.Sample[] samples=done.get(3,TimeUnit.SECONDS);
            check(failures.get()==0,"A valid bounded sample failed");
            for(int i=0;i<4;i++){check(bytes[i]<=4194304,"Round exceeded four MiB");check(samples[i]!=null&&samples[i].bytes==bytes[i],"Sample was not based on acknowledged DATA bytes");}
            for(FileLinkProbe probe:pair)probe.close();
            AtomicInteger rejected=new AtomicInteger();FileLinkProbe invalid=new FileLinkProbe(new FileLinkProbe.Host(){
                public boolean send(boolean lan,FileLinkPacket packet,BooleanSupplier current){return true;}
                public ScheduledExecutorService timer(){return timer;}
                public void done(FileRouteQualityPolicy.Sample[] samples){}
                public void failed(java.io.IOException error){rejected.incrementAndGet();}
            },nonce,generation,false);
            invalid.receive(FileLinkPacket.data(nonce,generation,0,0,new byte[1]),false);
            check(rejected.get()==1,"Payload before a negotiated sample was accepted");
            System.out.println("FILE probes: four alternating rounds, acknowledged byte counts, 16 MiB total bound, phase rejection passed");
        }finally{timer.shutdownNow();}
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
