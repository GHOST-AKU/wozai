package dev.ghost.nearbyim.core;
import java.lang.reflect.*;
import java.util.concurrent.*;
/** Reused hotspot and ordinary LAN are the same stream, not two candidates to benchmark. */
public final class ExistingHotspotQualityTests {
    public static void main(String[] args)throws Exception{
        try(var fixture=new AutomaticFileHandoverTests.Harness("lan",0,false,true)){
            CompletableFuture<Void> sending=CompletableFuture.runAsync(()->{try{fixture.transfer();}catch(Exception error){throw new CompletionException(error);}});
            if(!fixture.sourceReached.await(5,TimeUnit.SECONDS))throw new AssertionError("Hotspot fixture did not send real DATA");
            try{
                Object helper=field(fixture.routes.a.files,"handover");Method compare=helper.getClass().getDeclaredMethod("startQuality",boolean.class);compare.setAccessible(true);
                var actor=(ScheduledThreadPoolExecutor)field(fixture.routes.a.files,"actor");
                actor.submit(()->{try{
                    set(helper,"peerPhase",0);set(helper,"peerHeartbeat",System.nanoTime());set(helper,"peerCapacity",1_000_000_000L);set(helper,"peerCost",2_000_000_000L);
                    compare.invoke(helper,true);
                    Method busy=helper.getClass().getDeclaredMethod("busy");busy.setAccessible(true);
                    if((Boolean)busy.invoke(helper))throw new AssertionError("Reused hotspot attempted to pause/compare its own LAN stream");
                }catch(ReflectiveOperationException error){throw new CompletionException(error);}}).get(3,TimeUnit.SECONDS);
            }finally{fixture.sourceRelease.countDown();}
            sending.get(10,TimeUnit.SECONDS);
            System.out.println("Existing hotspot quality: one real DATA stream is reused without pausing or probing itself");
        }
    }
    static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    static void set(Object object,String name,Object value)throws ReflectiveOperationException{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
}
