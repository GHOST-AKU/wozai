package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Hold the real model queue while authenticated READY is followed by a close. */
public final class ReadyCloseTests {
    private static StreamConnection connection(Socket socket){return new StreamConnection(){public java.io.InputStream input()throws java.io.IOException{return socket.getInputStream();}public java.io.OutputStream output()throws java.io.IOException{return socket.getOutputStream();}public String label(){return "LAN";}public void close()throws java.io.IOException{socket.close();}};}
    private static void await(Callable<Boolean> condition)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){if(condition.call())return;Thread.sleep(5);}throw new AssertionError("Queued READY/close did not finish");}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("ready-close-");CountDownLatch release=new CountDownLatch(1);
        AtomicReference<Throwable> uncaught=new AtomicReference<>();AtomicReference<Thread> modelThread=new AtomicReference<>();AtomicReference<Thread.UncaughtExceptionHandler> originalHandler=new AtomicReference<>();
        AtomicReference<DesktopClient.State> state=new AtomicReference<>();AtomicBoolean publishedReady=new AtomicBoolean();
        DeviceIdentity phoneRoot=DeviceIdentity.generate();String phoneId=UUID.randomUUID().toString();
        InetAddress address=Collections.list(NetworkInterface.getNetworkInterfaces()).stream().flatMap(i->Collections.list(i.getInetAddresses()).stream()).filter(i->i instanceof Inet4Address&&LocalEndpoint.isLocal(i)).findFirst().orElseThrow();
        try(DesktopStore store=new DesktopStore(root);ServerSocket server=new ServerSocket(0);DesktopClient client=new DesktopClient(store,DesktopIdentity.load(root.resolve("identity.properties")),new DesktopClient.Listener(){
            public void changed(DesktopClient.State value){state.set(value);if(value.phase().equals("ready"))publishedReady.set(true);}
            public void request(DesktopClient.Request request){throw new AssertionError("Pinned peer asked for consent");}
            public void notice(UiText text){}
        })) {
            String endpoint=DesktopClient.endpoint(address,server.getLocalPort());store.peer(new DesktopStore.Peer(phoneId,"phone",phoneRoot.publicKey(),endpoint));client.connect(endpoint,phoneId).get(10,TimeUnit.SECONDS);
            try(Socket accepted=server.accept()) {
                CountDownLatch hello=new CountDownLatch(1);
                FramedSession phone=FramedSession.secure(connection(accepted),phoneId,"phone",phoneRoot,TestNoise.key(),false,new FramedSession.Listener(){
                    public void onHello(Frame frame){hello.countDown();}public void onReady(){}public void onText(Frame frame){}public void onAck(String id){}public void onClosed(UiText reason){}
                });
                try {
                    phone.start();if(!hello.await(10,TimeUnit.SECONDS))throw new AssertionError("Authenticated greeting missing");
                    Field current=DesktopClient.class.getDeclaredField("current");current.setAccessible(true);Object session=current.get(client);
                    Field wireField=session.getClass().getDeclaredField("wire");wireField.setAccessible(true);FramedSession wire=(FramedSession)wireField.get(session);
                    Field approved=FramedSession.class.getDeclaredField("approved");approved.setAccessible(true);await(()->approved.getBoolean(wire));
                    Field modelField=DesktopClient.class.getDeclaredField("model");modelField.setAccessible(true);ExecutorService model=(ExecutorService)modelField.get(client);CountDownLatch blocked=new CountDownLatch(1);
                    model.execute(()->{Thread thread=Thread.currentThread();modelThread.set(thread);originalHandler.set(thread.getUncaughtExceptionHandler());thread.setUncaughtExceptionHandler((t,error)->uncaught.set(error));blocked.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
                    if(!blocked.await(10,TimeUnit.SECONDS))throw new AssertionError("Model barrier missing");
                    phone.approve();await(wire::isReady);wire.close(UiText.EMPTY);release.countDown();
                    await(()->uncaught.get()!=null||state.get()!=null&&state.get().phase().equals("idle"));
                    if(uncaught.get()!=null)throw new AssertionError("Closed authenticated session crashed queued READY persistence",uncaught.get());
                    if(publishedReady.get()||!phoneRoot.publicKey().equals(store.peer(phoneId).publicKey()))throw new AssertionError("Closed READY changed trust or published a live connection");
                }finally{release.countDown();phone.close(UiText.EMPTY);}
            }
        }finally{
            release.countDown();Thread thread=modelThread.get();if(thread!=null)thread.setUncaughtExceptionHandler(originalHandler.get());
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
        System.out.println("ReadyCloseTests passed (real Noise consent, queued READY then close, no null pin/live-ready publication)");
    }
}
