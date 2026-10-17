package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.swing.SwingUtilities;

/** EDT-owned root cache. Storage and PNG decoding never run in a cell renderer. */
final class DesktopAvatars implements AutoCloseable {
    private static final long MAX_CACHE_BYTES=2L*1024*1024;
    private final Function<String,CompletableFuture<AvatarSnapshot>> source;
    private final Runnable refresh;
    private final LinkedHashMap<String,BufferedImage> cache=new LinkedHashMap<>(16,.75f,true);
    private final LinkedHashMap<String,Identicon.Model> models=new LinkedHashMap<>(32,.75f,true);
    private final Map<String,Long> loading=new HashMap<>();
    private final LinkedHashSet<String> absent=new LinkedHashSet<>();
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{Thread thread=new Thread(r,"avatar-display");thread.setDaemon(true);return thread;},new ThreadPoolExecutor.AbortPolicy());
    private long cacheBytes,generation;
    private boolean closed;
    DesktopAvatars(Function<String,CompletableFuture<AvatarSnapshot>> source,Runnable refresh){this.source=Objects.requireNonNull(source);this.refresh=Objects.requireNonNull(refresh);}
    BufferedImage image(String root){
        if(closed||root==null||root.isEmpty()||root.length()>344)return null;
        BufferedImage present=cache.get(root);if(present!=null)return present;
        if(absent.contains(root)||loading.containsKey(root)||loading.size()>=32)return null;
        Identicon.Model known=models.get(root);if(known==Identicon.neutral())return null;
        long token=++generation;loading.put(root,token);
        try{worker.execute(()->load(root,token,known));}catch(RejectedExecutionException stopped){complete(root,token,null,known);}
        return null;
    }
    Identicon.Model model(String root){if(closed||root==null||root.isEmpty()||root.length()>344)return Identicon.neutral();Identicon.Model present=models.get(root);if(present!=null)return present;image(root);return Identicon.neutral();}
    private void load(String root,long token,Identicon.Model known){
        final Identicon.Model resolved;try{resolved=known==null?Identicon.fromPublicKey(root):known;}catch(Exception invalid){SwingUtilities.invokeLater(()->complete(root,token,null,Identicon.neutral()));return;}
        SwingUtilities.invokeLater(()->{if(!closed&&Objects.equals(loading.get(root),token)){remember(root,resolved);refresh.run();}});
        try{source.apply(root).whenComplete((snapshot,error)->{
            if(error!=null||snapshot==null){SwingUtilities.invokeLater(()->complete(root,token,null,resolved));return;}
            try{worker.execute(()->{BufferedImage decoded=null;try{decoded=thumbnail(AvatarImages.decode(snapshot.png()));}catch(Exception|OutOfMemoryError invalid){}BufferedImage result=decoded;SwingUtilities.invokeLater(()->complete(root,token,result,resolved));});}
            catch(RejectedExecutionException stopped){SwingUtilities.invokeLater(()->complete(root,token,null,resolved));}
        });}catch(RuntimeException failed){SwingUtilities.invokeLater(()->complete(root,token,null,resolved));}
    }
    private static BufferedImage thumbnail(BufferedImage image){
        if(image==null||image.getWidth()<=128)return image;BufferedImage scaled=new BufferedImage(128,128,BufferedImage.TYPE_INT_ARGB);Graphics2D g=scaled.createGraphics();try{g.setComposite(AlphaComposite.Src);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(image,0,0,128,128,null);}finally{g.dispose();}return scaled;
    }
    private void remember(String root,Identicon.Model model){if(model==null)return;models.put(root,model);while(models.size()>128)models.remove(models.keySet().iterator().next());}
    private void complete(String root,long token,BufferedImage image,Identicon.Model model){
        if(closed||!Objects.equals(loading.get(root),token))return;loading.remove(root);
        remember(root,model);
        if(image==null){absent.add(root);while(absent.size()>64)absent.remove(absent.iterator().next());}
        else{cache.put(root,image);cacheBytes+=weight(image);while(cacheBytes>MAX_CACHE_BYTES||cache.size()>64){String eldest=cache.keySet().iterator().next();cacheBytes-=weight(cache.remove(eldest));}}
        refresh.run();
    }
    void invalidate(String root){BufferedImage previous=cache.remove(root);if(previous!=null)cacheBytes-=weight(previous);loading.remove(root);absent.remove(root);}
    private static long weight(BufferedImage image){return (long)image.getWidth()*image.getHeight()*4;}
    public void close(){closed=true;generation++;worker.shutdownNow();cache.clear();models.clear();loading.clear();absent.clear();cacheBytes=0;}
}
