package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.*;

public final class DesktopBatchTests {
    private static final class Events implements DesktopClient.Listener {
        DesktopClient client;volatile DesktopClient.State state;
        public void changed(DesktopClient.State state){this.state=state;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        public void notice(UiText text){}
    }
    public static void main(String[] args)throws Exception {
        Method batch,export,location;
        try{batch=DesktopClient.class.getMethod("sendAttachments",String.class,List.class);export=DesktopClient.class.getMethod("exportAttachment",String.class,AttachmentRecord.class,Path.class);location=DesktopClient.class.getMethod("attachmentLocation",String.class,AttachmentRecord.class);}
        catch(NoSuchMethodException missing){throw new AssertionError("Production batch/export-location API missing",missing);}
        Path root=Files.createTempDirectory("desktop-batch-");Events ea=new Events(),eb=new Events();Path ar=root.resolve("a"),br=root.resolve("b");Files.createDirectories(ar);Files.createDirectories(br);
        var ai=DesktopIdentity.load(ar.resolve("identity.properties"));var bi=DesktopIdentity.load(br.resolve("identity.properties"));
        try(DesktopStore as=new DesktopStore(ar);DesktopStore bs=new DesktopStore(br);DesktopClient a=new DesktopClient(as,ai,ea);DesktopClient b=new DesktopClient(bs,bi,eb)) {
            ea.client=a;eb.client=b;var listening=b.listen().get(10,TimeUnit.SECONDS);a.connect(listening.endpoints().get(0),bi.id()).get(10,TimeUnit.SECONDS);
            until(()->ea.state!=null&&eb.state!=null&&ea.state.phase().equals("ready")&&eb.state.phase().equals("ready"));
            // A slow extra queue-history writer must not overwrite a newer core receipt.
            Field current=DesktopClient.class.getDeclaredField("current");current.setAccessible(true);Object session=current.get(a);
            Field transfers=session.getClass().getDeclaredField("transfers");transfers.setAccessible(true);ClientAttachmentTransfers bridge=(ClientAttachmentTransfers)transfers.get(session);
            Field queue=ClientAttachmentTransfers.class.getDeclaredField("batch");queue.setAccessible(true);Object batchQueue=queue.get(bridge);
            Field observer=AttachmentBatchQueue.class.getDeclaredField("listener");observer.setAccessible(true);AttachmentBatchQueue.Listener original=(AttachmentBatchQueue.Listener)observer.get(batchQueue);
            var repeatedCoreWrites=new java.util.concurrent.atomic.AtomicInteger();
            bridge.batchListener(record->{if(!Set.of("queued","preparing","paused","failed","canceled").contains(record.state))repeatedCoreWrites.incrementAndGet();if(record.state.equals("offered"))try{Thread.sleep(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}original.changed(record);});
            Path first=root.resolve("第一份.txt"),second=root.resolve("second.txt");Files.write(first,new byte[]{1,2,3});Files.write(second,new byte[]{8,9});
            ((CompletableFuture<?>)batch.invoke(a,bi.id(),List.of(first,second))).get(10,TimeUnit.SECONDS);
            until(()->as.messages(bi.id()).stream().filter(m->m.attachment()!=null&&m.attachment().state.equals("delivered")).count()==2);
            until(()->bs.messages(ai.id()).stream().filter(m->m.attachment()!=null&&m.attachment().state.equals("received")).count()==2);
            if(repeatedCoreWrites.get()!=0)throw new AssertionError("Queue repeated "+repeatedCoreWrites.get()+" core history writes after their durable callbacks");
            if(!a.endToEndEncrypted(bi.id())||!b.endToEndEncrypted(ai.id()))throw new AssertionError("Batch left the production encrypted session");
            AttachmentRecord received=bs.messages(ai.id()).stream().map(DesktopStore.Message::attachment).filter(r->r!=null&&r.info.name.equals("第二份.txt")).findFirst().orElse(null);
            if(received==null)received=bs.messages(ai.id()).stream().map(DesktopStore.Message::attachment).filter(r->r!=null&&r.info.name.equals("second.txt")).findFirst().orElseThrow();
            Path named=(Path)((CompletableFuture<?>)location.invoke(b,ai.id(),received)).get(10,TimeUnit.SECONDS);
            if(!named.getFileName().toString().equals(received.info.name))throw new AssertionError("Opening the received file location exposes its internal UUID instead of the original filename");
            if(!Files.isSameFile(named,b.attachmentPath(ai.id(),received).get(5,TimeUnit.SECONDS)))throw new AssertionError("Named view duplicates received content instead of linking it");
            ((CompletableFuture<?>)export.invoke(b,ai.id(),received,named)).get(10,TimeUnit.SECONDS);
            if(!Files.isSameFile(named,b.attachmentPath(ai.id(),received).get(5,TimeUnit.SECONDS)))throw new AssertionError("Saving to the existing original-name entry replaced it with a redundant copy");
            Path destination=root.resolve("用户保存位置").resolve("new name.txt");Files.createDirectories(destination.getParent());
            ((CompletableFuture<?>)export.invoke(b,ai.id(),received,destination)).get(10,TimeUnit.SECONDS);
            Path actual=(Path)((CompletableFuture<?>)location.invoke(b,ai.id(),received)).get(10,TimeUnit.SECONDS);
            if(!actual.equals(destination.toAbsolutePath())||!Arrays.equals(Files.readAllBytes(destination),new byte[]{8,9}))throw new AssertionError("Open-location did not follow the successful save-as copy");
            bs.attachment(ai.id(),received);
            if(!((Path)((CompletableFuture<?>)location.invoke(b,ai.id(),received)).get(10,TimeUnit.SECONDS)).equals(destination.toAbsolutePath()))throw new AssertionError("Repeated saved receipt erased export location");
            Path privateCopy=b.attachmentPath(ai.id(),received).get(5,TimeUnit.SECONDS);Files.write(privateCopy,new byte[]{0,0});Files.write(destination,new byte[]{77});
            boolean refused=false;try{((CompletableFuture<?>)export.invoke(b,ai.id(),received,destination)).get(5,TimeUnit.SECONDS);}catch(ExecutionException expected){refused=true;}
            if(!refused||!Arrays.equals(Files.readAllBytes(destination),new byte[]{77}))throw new AssertionError("Damaged private photo overwrote user export before integrity validation");
            bs.clear(ai.id());if(!Files.exists(destination)||bs.exportLocation(ai.id(),received)!=null)throw new AssertionError("Chat clearing did not keep user file and remove only location metadata");
            System.out.println("DesktopBatchTests: real Noise two-file batch, private saved receipts, bytes and save-as location");
        }finally{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private interface Check{boolean test()throws Exception;}
    private static void until(Check check)throws Exception {long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){if(check.test())return;Thread.sleep(10);}throw new AssertionError("Actual desktop batch did not reach expected state");}
}
