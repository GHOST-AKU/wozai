package dev.ghost.nearbyim.core;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
/** A native dial that completes after rollback must be disposed, even while checkpoint work is pending. */
public final class LateFileLanCallbackTests {
    public static void main(String[] args)throws Exception{
        try(var h=new AutomaticFileHandoverTests.Harness("bluetooth")){
            h.routes.a.files.ensureReady().get(5,TimeUnit.SECONDS);h.routes.b.files.ensureReady().get(5,TimeUnit.SECONDS);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!h.routes.a.files.diagnostics().contains("file_handover_supported=true")&&System.nanoTime()<until)Thread.sleep(2);
            h.routes.a.platform.holdLan=true;h.routes.a.platform.connectEntered=new CountDownLatch(1);
            Object helper=field(h.routes.a.files,"handover");var routing=(ScheduledThreadPoolExecutor)field(h.routes.a.files,"actor");
            Object engine=field(h.files[0],"streaming");var fileActor=(ExecutorService)field(engine,"actor");CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
            fileActor.submit(()->{held.countDown();try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
            if(!held.await(2,TimeUnit.SECONDS))throw new AssertionError("File actor hold failed");
            BlockingConnection late=new BlockingConnection();
            try{
                Method begin=helper.getClass().getDeclaredMethod("begin",String.class,boolean.class);begin.setAccessible(true);
                routing.submit(()->invoke(begin,helper,UUID.randomUUID().toString(),false)).get(2,TimeUnit.SECONDS);
                if(!h.routes.a.platform.connectEntered.await(2,TimeUnit.SECONDS))throw new AssertionError("LAN dial was not held");
                Method fail=helper.getClass().getDeclaredMethod("candidateFailed",IOException.class);fail.setAccessible(true);
                routing.submit(()->invoke(fail,helper,new IOException("Canceled standby"))).get(2,TimeUnit.SECONDS);
                h.routes.a.platform.dialOpened.accept(late);
                if(!late.closed.await(1,TimeUnit.SECONDS))throw new AssertionError("Late LAN callback opened a new lane during rollback");
                System.out.println("Late LAN callback: canceled standby sockets are disposed while checkpoint rollback is pending");
            }finally{release.countDown();late.close();}
        }
    }
    static Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    static void invoke(Method method,Object object,Object...args){try{method.invoke(object,args);}catch(Exception error){throw new CompletionException(error);}}
    static final class BlockingConnection implements StreamConnection {
        final CountDownLatch closed=new CountDownLatch(1);
        public InputStream input(){return new InputStream(){public int read()throws IOException{try{closed.await();return -1;}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}}};}
        public OutputStream output(){return new ByteArrayOutputStream();}public String label(){return "late";}public void close(){closed.countDown();}
    }
}
