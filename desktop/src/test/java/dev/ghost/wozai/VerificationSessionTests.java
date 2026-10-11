package dev.ghost.wozai;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class VerificationSessionTests {
    private static Method view,verify;
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static final class Listener implements DesktopClient.Listener {
        DesktopClient client;volatile DesktopClient.State state;
        public void changed(DesktopClient.State state){this.state=state;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        public void notice(UiText notice){}
    }
    private static IdentityVerification view(DesktopClient client,String peer)throws Exception{return (IdentityVerification)((CompletableFuture<?>)view.invoke(client,peer)).get(10,TimeUnit.SECONDS);}
    private static boolean verify(DesktopClient client,IdentityVerification snapshot,String scan)throws Exception{return (Boolean)((CompletableFuture<?>)verify.invoke(client,snapshot,scan)).get(10,TimeUnit.SECONDS);}
    private static void ready(Listener a,Listener b)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(a.state!=null&&b.state!=null&&a.state.phase().equals("ready")&&b.state.phase().equals("ready"))return;Thread.sleep(10);}throw new AssertionError("Actual encrypted clients did not become ready");}
    public static void main(String[] args)throws Exception {
        try{view=DesktopClient.class.getMethod("verification",String.class);verify=DesktopClient.class.getMethod("verifyIdentity",IdentityVerification.class,String.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("Production session identity verification API is missing",absent);}
        Path root=Files.createTempDirectory("verify-session-");Path pa=root.resolve("a"),pb=root.resolve("b");Files.createDirectories(pa);Files.createDirectories(pb);
        DesktopIdentity.Identity ia=DesktopIdentity.load(pa.resolve("identity.properties")),ib=DesktopIdentity.load(pb.resolve("identity.properties"));Listener la=new Listener(),lb=new Listener();
        try(DesktopStore sa=new DesktopStore(pa);DesktopStore sb=new DesktopStore(pb);DesktopClient a=new DesktopClient(sa,ia,la);DesktopClient b=new DesktopClient(sb,ib,lb)){
            la.client=a;lb.client=b;DesktopClient.Listening listening=b.listen().get(10,TimeUnit.SECONDS);String address=listening.endpoints().get(0);
            check(view(a,ib.id())==null,"no security-code snapshot before authenticated consent");
            a.connect(address,ib.id()).get(10,TimeUnit.SECONDS);ready(la,lb);
            IdentityVerification av=view(a,ib.id()),bv=view(b,ia.id());check(!av.verified&&!bv.verified,"ready/TOFU does not grant manual verification");
            check(av.code.hex().equals(bv.code.hex()),"production endpoints show identical code");
            check(!verify(a,av,"WOZAI-ID1:"+"0".repeat(64))&&!view(a,ib.id()).verified,"wrong QR cannot persist a verified identity");
            check(verify(a,av,bv.code.qrPayload())&&view(a,ib.id()).verified,"opposite endpoint QR saves verified exact roots");
            check(!view(b,ia.id()).verified,"verification state is local, not claimed by peer");
            a.disconnect().get(10,TimeUnit.SECONDS);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(b.endToEndEncrypted(ia.id())&&System.nanoTime()<end)Thread.sleep(10);
            check(!verify(a,av,null),"closed-page snapshot cannot mark verified");
            a.connect(address,ib.id()).get(10,TimeUnit.SECONDS);ready(la,lb);IdentityVerification reconnected=view(a,ib.id());
            check(reconnected.verified&&reconnected.code.hex().equals(av.code.hex()),"reconnect keeps verified roots and stable code");
            check(!verify(a,av,null),"old connection generation cannot verify a new session");
            a.revoke(ib.id()).get(10,TimeUnit.SECONDS);check(!sa.isVerified(ib.id(),ia.signer().publicKey(),ib.signer().publicKey()),"actual revoke clears stored verification");
            check(!verify(a,reconnected,null),"delayed confirmation cannot restore revoked trust");
            System.out.println("VerificationSessionTests: "+checks+" actual Noise/factory/scan/reconnect/revoke checks passed");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
