package dev.ghost.wozai;

import dev.ghost.nearbyim.core.DeviceIdentity;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Asynchronous root-bound cache with real private avatar stores and invalidated late callbacks. */
public final class DesktopAvatarTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static <T>T edt(Callable<T> work)throws Exception{FutureTask<T> result=new FutureTask<>(work);SwingUtilities.invokeAndWait(result);return result.get();}
    private static Object invoke(Object target,String name,Class<?>[] types,Object...args)throws Exception{Method method=target.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(target,args);}
    private static void await(Callable<Boolean> work,String message)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(System.nanoTime()<end){if(edt(work))return;Thread.sleep(10);}throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Class<?> production;try{production=Class.forName("dev.ghost.wozai.DesktopAvatars");}catch(ClassNotFoundException missing){throw new AssertionError("Root-bound desktop avatar cache is missing",missing);}
        Class<?> storage=Class.forName("dev.ghost.nearbyim.core.AvatarStore");Path root=Files.createTempDirectory("desktop-avatars-");Object cache=null;
        try {
            String local=DeviceIdentity.generate().publicKey(),remote=DeviceIdentity.generate().publicKey();Object localStore=storage.getConstructor(Path.class,String.class).newInstance(root.resolve("local"),local),remoteStore=storage.getConstructor(Path.class,String.class).newInstance(root.resolve("remote"),remote);
            BufferedImage red=new BufferedImage(256,256,BufferedImage.TYPE_INT_RGB);for(int y=0;y<256;y++)for(int x=0;x<256;x++)red.setRGB(x,y,0xff0000);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(red,"png",out);
            Object localSnapshot=invoke(localStore,"save",new Class<?>[]{long.class,byte[].class},0L,out.toByteArray()),remoteSnapshot=invoke(remoteStore,"snapshot",new Class<?>[0]);
            Map<String,AtomicInteger> requests=new ConcurrentHashMap<>();AtomicReference<CompletableFuture<Object>> delayed=new AtomicReference<>(new CompletableFuture<>());AtomicInteger repaints=new AtomicInteger();
            Function<String,CompletableFuture<Object>> source=key->{requests.computeIfAbsent(key,ignored->new AtomicInteger()).incrementAndGet();return key.equals(local)?CompletableFuture.completedFuture(localSnapshot):delayed.get();};
            Constructor<?> constructor=production.getDeclaredConstructor(Function.class,Runnable.class);constructor.setAccessible(true);Object model=constructor.newInstance(source,(Runnable)repaints::incrementAndGet);cache=model;
            check(edt(()->invoke(model,"image",new Class<?>[]{String.class},local))==null,"First lookup blocked EDT for image I/O");await(()->invoke(model,"image",new Class<?>[]{String.class},local)!=null,"Local avatar never entered cache");
            BufferedImage loaded=edt(()->(BufferedImage)invoke(model,"image",new Class<?>[]{String.class},local));check((loaded.getRGB(20,20)&0xffffff)==0xff0000,"Local saved avatar was not decoded");
            edt(()->{invoke(model,"image",new Class<?>[]{String.class},remote);invoke(model,"invalidate",new Class<?>[]{String.class},remote);return null;});delayed.get().complete(localSnapshot);Thread.sleep(100);
            check(edt(()->invoke(model,"image",new Class<?>[]{String.class},remote))==null,"Invalidated callback assigned local photo to remote root");delayed.set(CompletableFuture.completedFuture(remoteSnapshot));edt(()->{invoke(model,"invalidate",new Class<?>[]{String.class},remote);return null;});check(edt(()->invoke(model,"image",new Class<?>[]{String.class},remote))==null,"Empty remote snapshot became local avatar");
            check(edt(()->invoke(model,"image",new Class<?>[]{String.class},"Nickname"))==null,"Untrusted nickname was used as root");
            Field loading=production.getDeclaredField("loading");loading.setAccessible(true);
            await(()->!((Map<?,?>)loading.get(model)).containsKey("Nickname"),"Invalid root validation never completed");
            check(!requests.containsKey("Nickname"),"Untrusted root reached private avatar storage");
            Field bytes=production.getDeclaredField("cacheBytes");bytes.setAccessible(true);check(bytes.getLong(model)<=2L*1024*1024,"Desktop avatar cache exceeds 2 MiB");
            check(repaints.get()>0,"Async image completion did not refresh display");
            AppTheme.Avatar untrusted=edt(()->{AppTheme.Avatar view=new AppTheme.Avatar("",loaded);view.setSize(48,48);return view;});BufferedImage neutral=new BufferedImage(48,48,BufferedImage.TYPE_INT_RGB);edt(()->{var graphics=neutral.createGraphics();try{untrusted.paint(graphics);}finally{graphics.dispose();}return null;});check((neutral.getRGB(24,24)&0xffffff)!=0xff0000,"Untrusted avatar displayed a photo instead of a neutral tile");
            retainedViews(production,localSnapshot);
            edt(()->{invoke(model,"close",new Class<?>[0]);return null;});check(edt(()->invoke(model,"image",new Class<?>[]{String.class},local))==null,"Closed cache accepted image callbacks");
            System.out.println("DesktopAvatarTests: "+checks+" checks passed");
        }finally{if(cache!=null){Object model=cache;edt(()->{invoke(model,"close",new Class<?>[0]);return null;});}try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static void retainedViews(Class<?> production,Object snapshot)throws Exception {
        Constructor<?> constructor=production.getDeclaredConstructor(Function.class,Runnable.class);constructor.setAccessible(true);Object model=constructor.newInstance((Function<String,CompletableFuture<Object>>)key->CompletableFuture.completedFuture(snapshot),(Runnable)()->{});List<AppTheme.Avatar> retained=new ArrayList<>();
        try{
            for(int i=0;i<40;i++){String key=DeviceIdentity.generate().publicKey();edt(()->invoke(model,"image",new Class<?>[]{String.class},key));await(()->invoke(model,"image",new Class<?>[]{String.class},key)!=null,"Avatar eviction fixture did not load");retained.add(edt(()->{AppTheme.Avatar view=new AppTheme.Avatar(key,(BufferedImage)invoke(model,"image",new Class<?>[]{String.class},key),(dev.ghost.nearbyim.core.Identicon.Model)invoke(model,"model",new Class<?>[]{String.class},key));view.setSize(48,48);return view;}));}
            Field bytes=production.getDeclaredField("cacheBytes");bytes.setAccessible(true);check(bytes.getLong(model)<=2L*1024*1024,"Avatar views expanded cache beyond 2 MiB");
            System.gc();Thread.sleep(100);BufferedImage old=new BufferedImage(48,48,BufferedImage.TYPE_INT_RGB);edt(()->{var g=old.createGraphics();try{retained.get(0).paint(g);}finally{g.dispose();}return null;});check((old.getRGB(24,24)&0xffffff)!=0xff0000,"Offscreen native views retained evicted avatar pixels");
        }finally{edt(()->{invoke(model,"close",new Class<?>[0]);return null;});}
    }
}
