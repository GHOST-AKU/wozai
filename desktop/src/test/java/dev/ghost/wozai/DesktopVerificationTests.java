package dev.ghost.wozai;
import dev.ghost.nearbyim.core.DeviceIdentity;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.UUID;

public final class DesktopVerificationTests {
    private static Method mark,verified,invalidate;
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static boolean isVerified(DesktopStore store,String id,String local,String remote)throws Exception{return (Boolean)verified.invoke(store,id,local,remote);}
    public static void main(String[] args)throws Exception {
        try{mark=DesktopStore.class.getMethod("markVerified",String.class,String.class,String.class);verified=DesktopStore.class.getMethod("isVerified",String.class,String.class,String.class);invalidate=DesktopStore.class.getMethod("invalidateLocalVerifications",String.class);}
        catch(NoSuchMethodException absent){throw new AssertionError("Persistent verification bound to both roots is missing",absent);}
        Path directory=Files.createTempDirectory("verification-store-");String id=UUID.randomUUID().toString(),a=DeviceIdentity.generate().publicKey(),b=DeviceIdentity.generate().publicKey(),other=DeviceIdentity.generate().publicKey();
        try{
            try(DesktopStore store=new DesktopStore(directory)){
                store.peer(new DesktopStore.Peer(id,"Friend",b,"old-address"));
                check(!isVerified(store,id,a,b),"TOFU trust is not manual verification");mark.invoke(store,id,a,b);
                check(isVerified(store,id,a,b),"matching compared roots are verified");
                store.peer(new DesktopStore.Peer(id,"Renamed",b,"new-address"));check(isVerified(store,id,a,b),"rename/reconnect preserves verification");
            }
            try(DesktopStore store=new DesktopStore(directory)){
                check(isVerified(store,id,a,b),"verification survives app restart");
                check(!isVerified(store,id,other,b),"local key substitution is not verified");invalidate.invoke(store,other);
                check(!isVerified(store,id,a,b),"local rotation clears old marker permanently");mark.invoke(store,id,a,b);
                store.peer(new DesktopStore.Peer(id,"Changed",other,"address"));check(!isVerified(store,id,a,other),"remote root change clears verification");
                boolean rejected=false;try{mark.invoke(store,id,a,b);}catch(InvocationTargetException error){rejected=error.getCause() instanceof java.io.IOException;}
                check(rejected,"stale comparison cannot mark another saved root");
                mark.invoke(store,id,a,other);store.revoke(id);check(!isVerified(store,id,a,other),"revocation clears manual verification");
                store.peer(new DesktopStore.Peer(id,"Repaired",other,"address"));check(!isVerified(store,id,a,other),"re-pairing cannot inherit old verification");
            }
            System.out.println("DesktopVerificationTests: "+checks+" persistence/root-change/revoke checks passed");
        }finally{try(var paths=Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
