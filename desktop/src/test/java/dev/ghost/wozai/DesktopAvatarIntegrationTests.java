package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;

/** Independent local profiles and actual encrypted contact-avatar delivery. */
public final class DesktopAvatarIntegrationTests {
    private static int checks;
    private static Method local,contact,save,reset;
    private static final class Events implements DesktopClient.Listener {
        DesktopClient client;volatile DesktopClient.State state;
        public void changed(DesktopClient.State value){state=value;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        public void notice(UiText value){}
    }
    public static void main(String[] args)throws Exception {
        try {
            local=DesktopClient.class.getMethod("localAvatar");
            contact=DesktopClient.class.getMethod("avatar",String.class);
            save=DesktopClient.class.getMethod("saveAvatar",long.class,byte[].class);
            reset=DesktopClient.class.getMethod("resetAvatar",long.class);
        }catch(NoSuchMethodException missing){throw new AssertionError("Desktop has no independent local and contact avatar API",missing);}
        Path directory=Files.createTempDirectory("desktop-avatar-integration-");
        Path ap=directory.resolve("a"),bp=directory.resolve("b");Files.createDirectories(ap);Files.createDirectories(bp);
        var ai=DesktopIdentity.load(ap.resolve("identity.properties"));var bi=DesktopIdentity.load(bp.resolve("identity.properties"));
        Events ea=new Events(),eb=new Events();
        try(DesktopStore as=new DesktopStore(ap);DesktopStore bs=new DesktopStore(bp);DesktopClient a=new DesktopClient(as,ai,ea);DesktopClient b=new DesktopClient(bs,bi,eb)) {
            ea.client=a;eb.client=b;
            check(bytes(get(local,a)).length==0&&bytes(get(local,b)).length==0,"Fresh devices do not start with defaults");
            byte[] red=png(0xffff0000),blue=png(0xff0000ff);
            Object aa=get(save,a,0L,red),bb=get(save,b,0L,blue);
            check(!hash(aa).equals(hash(bb)),"Independent device settings were combined");
            check(Arrays.equals(bytes(get(local,a)),red)&&Arrays.equals(bytes(get(local,b)),blue),"Save changed another device's local avatar");
            var listening=b.listen().get(10,TimeUnit.SECONDS);a.connect(listening.endpoints().get(0),bi.id()).get(10,TimeUnit.SECONDS);
            until(()->ea.state!=null&&eb.state!=null&&ea.state.phase().equals("ready")&&eb.state.phase().equals("ready"));
            until(()->Arrays.equals(bytes(get(contact,b,ai.signer().publicKey())),red)&&Arrays.equals(bytes(get(contact,a,bi.signer().publicKey())),blue));
            check(Arrays.equals(bytes(get(local,b)),blue),"Received contact avatar replaced this device's own avatar");
            check(a.endToEndEncrypted(bi.id())&&b.endToEndEncrypted(ai.id()),"Avatar delivery bypassed the encrypted production session");
            get(reset,a,revision(aa));
            until(()->bytes(get(contact,b,ai.signer().publicKey())).length==0);
            check(Arrays.equals(bytes(get(local,b)),blue),"Remote reset also reset the other device's own avatar");
            a.send("message after avatar reset").get(5,TimeUnit.SECONDS);
            until(()->bs.messages(ai.id()).stream().anyMatch(m->m.body().equals("message after avatar reset")));
            check(ea.state.phase().equals("ready")&&eb.state.phase().equals("ready"),"Changing avatar interrupted chat");
            a.disconnect().get(5,TimeUnit.SECONDS);until(()->eb.state.phase().equals("idle"));
            Object afterReset=get(local,a);get(save,a,revision(afterReset),red);
            a.connect(listening.endpoints().get(0),bi.id()).get(10,TimeUnit.SECONDS);
            until(()->ea.state.phase().equals("ready")&&eb.state.phase().equals("ready"));
            until(()->Arrays.equals(bytes(get(contact,b,ai.signer().publicKey())),red));
            check(Arrays.equals(bytes(get(local,b)),blue),"Reconnect copied contact settings into the local profile");
            Object before=get(local,a);boolean stale=false;
            try{get(save,a,0L,blue);}catch(ExecutionException expected){stale=true;}
            check(stale&&hash(get(local,a)).equals(hash(before)),"A stale edit overwrote the current avatar");
            System.out.println("DesktopAvatarIntegrationTests: "+checks+" checks passed; independent device profiles, real Noise contact delivery/reset/reconnect, chat and stale-edit guard");
        }finally{try(var files=Files.walk(directory)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static byte[] png(int color)throws Exception {
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);for(int y=0;y<2;y++)for(int x=0;x<2;x++)image.setRGB(x,y,color);
        ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
    }
    private static Object get(Method method,Object target,Object... args)throws Exception{return ((CompletableFuture<?>)method.invoke(target,args)).get(5,TimeUnit.SECONDS);}
    private static byte[] bytes(Object snapshot)throws Exception{return (byte[])snapshot.getClass().getMethod("png").invoke(snapshot);}
    private static Object profile(Object snapshot)throws Exception{return snapshot.getClass().getMethod("profile").invoke(snapshot);}
    private static String hash(Object snapshot)throws Exception{Object p=profile(snapshot);return (String)p.getClass().getField("avatarHash").get(p);}
    private static long revision(Object snapshot)throws Exception{Object p=profile(snapshot);return p.getClass().getField("revision").getLong(p);}
    private interface Check {boolean test()throws Exception;}
    private static void until(Check value)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(value.test())return;Thread.sleep(10);}throw new AssertionError("Production avatar clients did not reach the expected state");}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);checks++;}
}
