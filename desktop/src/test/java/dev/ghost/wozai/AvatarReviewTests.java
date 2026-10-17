package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.lang.reflect.*;
import java.security.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import javax.swing.*;

/** Real 16-bit PNGs, a cryptographic provider probe and native full-corner identicon pixels. */
public final class AvatarReviewTests {
    private static int checks;
    private static <T>T edt(Callable<T> work)throws Exception{FutureTask<T> result=new FutureTask<>(work);SwingUtilities.invokeAndWait(result);return result.get();}
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void until(Callable<Boolean> work)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(System.nanoTime()<end){if(edt(work))return;Thread.sleep(10);}throw new AssertionError("Avatar background work did not complete");}
    private static AppTheme.Avatar widget(String root,BufferedImage image,Identicon.Model model)throws Exception {
        try{Constructor<AppTheme.Avatar> constructor=AppTheme.Avatar.class.getDeclaredConstructor(String.class,BufferedImage.class,Identicon.Model.class);constructor.setAccessible(true);return constructor.newInstance(root,image,model);}
        catch(NoSuchMethodException missing){return new AppTheme.Avatar(root,image);}
    }
    public static void main(String[] args)throws Exception {
        String mode=args.length==0?"all":args[0];if(mode.equals("all")||mode.equals("depth"))depth();if(mode.equals("all")||mode.equals("edt"))cryptography();if(mode.equals("all")||mode.equals("corners"))corners();System.out.println("AvatarReviewTests: "+checks+" checks passed ("+mode+")");
    }
    static void depth()throws Exception {
        byte[] png=rgba16();BufferedImage image=AvatarImages.decode(png);check(image.getType()==BufferedImage.TYPE_INT_ARGB,"16-bit avatar decode retained an unaccounted ushort raster");check(image.getRaster().getDataBuffer() instanceof DataBufferInt,"Avatar pixels are not normalized to four bytes each");check(image.getRGB(128,128)>>>24==128,"16-bit alpha did not normalize to 8-bit RGBA");
        String root=DeviceIdentity.generate().publicKey(),hash=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png));AvatarSnapshot snapshot=new AvatarSnapshot(new AvatarProfile(1,hash,"png",256,256,png.length),png);DesktopAvatars cache=new DesktopAvatars(key->CompletableFuture.completedFuture(snapshot),()->{});
        try{edt(()->cache.image(root));until(()->cache.image(root)!=null);BufferedImage cached=edt(()->cache.image(root));Field bytes=DesktopAvatars.class.getDeclaredField("cacheBytes");bytes.setAccessible(true);DataBufferInt data=(DataBufferInt)cached.getRaster().getDataBuffer();check(bytes.getLong(cache)==(long)data.getSize()*data.getNumBanks()*4,"Avatar cache counts fewer bytes than its actual normalized raster");check(cached.getWidth()<=128,"Display cache retained full 256px pixels instead of a bounded thumbnail");}finally{edt(()->{cache.close();return null;});}
    }
    static void cryptography()throws Exception {
        String root=DeviceIdentity.generate().publicKey();Identicon.Model known=Identicon.fromPublicKey(root);CountingDigest.onEdt.set(0);CountingDigest.offEdt.set(0);Provider provider=new Provider("AvatarEdtProbe","1.0","Test cryptographic thread affinity"){ {put("MessageDigest.SHA-256",CountingDigest.class.getName());} };Security.insertProviderAt(provider,1);AtomicInteger refreshed=new AtomicInteger();DesktopAvatars cache=new DesktopAvatars(key->CompletableFuture.completedFuture(new AvatarSnapshot(AvatarProfile.defaults(0),new byte[0])),refreshed::incrementAndGet);
        try{
            edt(()->{for(int i=0;i<40;i++){cache.image(root);widget(root,null,known);}return null;});until(()->refreshed.get()>0);edt(()->{for(int i=0;i<40;i++){cache.image(root);widget(root,null,known);}return null;});check(CountingDigest.onEdt.get()==0,"Avatar lookup or widget creation performed SHA-256 on EDT: "+CountingDigest.onEdt.get());check(CountingDigest.offEdt.get()>0,"Provider probe did not observe background identicon derivation");check(CountingDigest.offEdt.get()==1,"Repeated render lookups derived the same public-root model more than once");
            int before=CountingDigest.offEdt.get();edt(()->{cache.invalidate(root);cache.image(root);return null;});until(()->refreshed.get()>1);check(CountingDigest.offEdt.get()==before,"Profile image invalidation recomputed immutable root geometry");
        }finally{edt(()->{cache.close();return null;});Security.removeProvider(provider.getName());}
    }
    static void corners()throws Exception {
        String root;Identicon.Model model;do{root=DeviceIdentity.generate().publicKey();model=Identicon.fromPublicKey(root);}while(!model.filled(0,0)||!model.filled(0,6));String publicRoot=root;Identicon.Model pattern=model;
        for(int size:new int[]{32,48,96}){BufferedImage pixels=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);edt(()->{AppTheme.Avatar view=widget(publicRoot,null,pattern);view.setSize(size,size);Graphics2D graphics=pixels.createGraphics();try{view.paint(graphics);}finally{graphics.dispose();}return null;});int low=(int)Math.round(size/9d),high=(int)Math.round(size*8/9d)-1;for(int y:new int[]{low,high})for(int x:new int[]{low,high})check(pixels.getRGB(x,y)==pattern.foreground(AppTheme.dark),"Default icon clipped a filled fingerprint corner at "+size+"px ("+x+","+y+")");}
    }
    public static final class CountingDigest extends MessageDigestSpi {
        static final AtomicInteger onEdt=new AtomicInteger(),offEdt=new AtomicInteger();
        private final MessageDigest delegate;
        public CountingDigest(){try{delegate=MessageDigest.getInstance("SHA-256","SUN");if(SwingUtilities.isEventDispatchThread())onEdt.incrementAndGet();else offEdt.incrementAndGet();}catch(GeneralSecurityException unavailable){throw new IllegalStateException(unavailable);}}
        protected void engineUpdate(byte value){delegate.update(value);}protected void engineUpdate(byte[] values,int start,int length){delegate.update(values,start,length);}protected byte[] engineDigest(){return delegate.digest();}protected void engineReset(){delegate.reset();}
    }
    private static void chunk(DataOutputStream out,String type,byte[] data)throws IOException{byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(data.length);out.write(name);out.write(data);CRC32 crc=new CRC32();crc.update(name);crc.update(data);out.writeInt((int)crc.getValue());}
    private static byte[] rgba16()throws IOException {
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(encoded);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);h.writeInt(256);h.writeInt(256);h.write(new byte[]{16,6,0,0,0});chunk(out,"IHDR",header.toByteArray());ByteArrayOutputStream compressed=new ByteArrayOutputStream();try(DataOutputStream pixels=new DataOutputStream(new DeflaterOutputStream(compressed))){for(int y=0;y<256;y++){pixels.writeByte(0);for(int x=0;x<256;x++){pixels.writeShort(0xffff);pixels.writeShort(0x4000);pixels.writeShort(0x2000);pixels.writeShort(0x8000);}}}chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return encoded.toByteArray();
    }
}
