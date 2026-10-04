package dev.ghost.wozai;
import dev.ghost.nearbyim.core.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.net.*;
import dev.ghost.nearbyim.i18n.UiText;
public final class AttachmentStoreTests {
    static final String PEER="12345678-1234-1234-1234-123456789abc",ID="12345678-1234-1234-1234-123456789abd";
    static final class Events implements DesktopClient.Listener {
        final AtomicReference<DesktopClient.State> state=new AtomicReference<>();
        public void changed(DesktopClient.State value){state.set(value);}
        public void request(DesktopClient.Request request){throw new AssertionError("Pinned peer asked for consent");}
        public void notice(UiText text){}
        boolean ready(){return state.get()!=null&&state.get().phase().equals("ready");}
    }
    static void await(java.util.concurrent.Callable<Boolean> condition)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(10);}throw new AssertionError("Desktop attachment timed out");}
    static AttachmentRecord record(DesktopStore store,String peer,String id)throws Exception{return store.messages(peer).stream().filter(m->m.id().equals(id)).findFirst().map(DesktopStore.Message::attachment).orElse(null);}
    static void clientTransfer(Path root)throws Exception {
        Path a=root.resolve("a"),b=root.resolve("b");Files.createDirectories(a);Files.createDirectories(b);var ai=DesktopIdentity.load(a.resolve("identity.properties"));var bi=DesktopIdentity.load(b.resolve("identity.properties"));Events ae=new Events(),be=new Events();
        InetAddress address=Collections.list(NetworkInterface.getNetworkInterfaces()).stream().flatMap(i->Collections.list(i.getInetAddresses()).stream()).filter(i->i instanceof Inet4Address&&LocalEndpoint.isLocal(i)).findFirst().orElseThrow();
        Path retainedPhoto=null;AttachmentInfo sentPhoto=null;
        try(DesktopStore as=new DesktopStore(a);DesktopStore bs=new DesktopStore(b);DesktopClient ac=new DesktopClient(as,ai,ae);DesktopClient bc=new DesktopClient(bs,bi,be)){
            int port=bc.listen().get(10,TimeUnit.SECONDS).port();String endpoint=DesktopClient.endpoint(address,port);as.peer(new DesktopStore.Peer(bi.id(),"b",bi.signer().publicKey(),endpoint));bs.peer(new DesktopStore.Peer(ai.id(),"a",ai.signer().publicKey(),""));ac.connect(endpoint,bi.id()).get(10,TimeUnit.SECONDS);await(()->ae.ready()&&be.ready());
            byte[] data=new byte[32768*8+7];new Random(91).nextBytes(data);Path source=root.resolve("document.pdf");Files.write(source,data);
            String id=ac.sendAttachment(bi.id(),source).get(10,TimeUnit.SECONDS);await(()->record(as,bi.id(),id)!=null&&record(bs,ai.id(),id)!=null&&record(as,bi.id(),id).state.equals("delivered")&&record(bs,ai.id(),id).state.equals("received"));AttachmentRecord offered=record(bs,ai.id(),id);
            Path received=bc.attachmentPath(ai.id(),offered.info).get(10,TimeUnit.SECONDS);if(!Arrays.equals(data,Files.readAllBytes(received))||!received.toString().endsWith(".pdf"))throw new AssertionError("Desktop attachment bytes or extension changed");
            Path photo=root.resolve("photo.png");Files.write(photo,data);String photoId=ac.sendAttachment(bi.id(),photo).get(10,TimeUnit.SECONDS);
            await(()->record(as,bi.id(),photoId)!=null&&record(as,bi.id(),photoId).state.equals("delivered"));AttachmentRecord sent=record(as,bi.id(),photoId);
            Path retained=ac.attachmentPath(bi.id(),sent).get(10,TimeUnit.SECONDS);if(!Arrays.equals(data,Files.readAllBytes(retained)))throw new AssertionError("Sent photo was not retained");
            retainedPhoto=retained;sentPhoto=sent.info;
            Path exported=root.resolve("exported.pdf");Files.copy(received,exported);bc.clear(ai.id()).get(10,TimeUnit.SECONDS);if(Files.exists(received)||!Files.exists(exported)||bs.peer(ai.id()).publicKey().isEmpty())throw new AssertionError("Clear damaged independent export or trust");
            boolean refused=false;try{ac.sendAttachment(UUID.randomUUID().toString(),source).get(10,TimeUnit.SECONDS);}catch(ExecutionException expected){refused=true;}if(!refused)throw new AssertionError("Wrong peer selector sent a file");
        }
        try(DesktopStore restarted=new DesktopStore(a)){if(!Files.exists(restarted.attachmentFile(bi.id(),sentPhoto,true)))throw new AssertionError("Restart removed delivered photo");restarted.clear(bi.id());if(Files.exists(retainedPhoto))throw new AssertionError("Clear retained sent photo bytes");}
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("attachment-store-");
        AttachmentInfo info=new AttachmentInfo(ID,"photo.jpg","image/jpeg",10,"0".repeat(64),1);
        try(DesktopStore store=new DesktopStore(root)) {
            store.peer(new DesktopStore.Peer(PEER,"peer","",""));
            Method save;
            try{save=DesktopStore.class.getMethod("attachment",String.class,AttachmentRecord.class);}
            catch(NoSuchMethodException e){throw new AssertionError("Desktop attachment persistence is missing");}
            save.invoke(store,PEER,new AttachmentRecord(info,true,"awaitingReceipt",10));
        }
        try(DesktopStore store=new DesktopStore(root)) {
            Object record=DesktopStore.Message.class.getMethod("attachment").invoke(store.messages(PEER).get(0));
            if(!((AttachmentRecord)record).state.equals("unknown"))throw new AssertionError("Unacknowledged send falsely completed after restart");
            store.clear(PEER);if(!store.messages(PEER).isEmpty())throw new AssertionError("History retained");
        }
        clientTransfer(root.resolve("pair"));
        System.out.println("AttachmentStoreTests passed");
    }
}
