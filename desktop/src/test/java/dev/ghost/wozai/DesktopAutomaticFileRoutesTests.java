package dev.ghost.wozai;

import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Noise CHAT over a simulated Bluetooth stream, separate ordinary LAN FILE sockets. */
public final class DesktopAutomaticFileRoutesTests {
    private static final class Events implements DesktopClient.Listener {
        DesktopClient client; volatile DesktopClient.State state;
        public void changed(DesktopClient.State value){state=value;}
        public void request(DesktopClient.Request request){client.approve(request,true);}
        volatile String notice="";public void notice(UiText value){notice=value.key;}
    }
    private static StreamConnection bluetooth(Socket socket){return new StreamConnection(){
        public InputStream input()throws IOException{return socket.getInputStream();}
        public OutputStream output()throws IOException{return socket.getOutputStream();}
        public String label(){return "Bluetooth";}
        public void close()throws IOException{socket.close();}
    };}
    private static void outgoing(DesktopClient client,StreamConnection connection,String peer)throws Exception{
        Method submit=DesktopClient.class.getDeclaredMethod("submit",Callable.class);submit.setAccessible(true);
        Method attach=DesktopClient.class.getDeclaredMethod("attach",StreamConnection.class,boolean.class,String.class,String.class,long.class);attach.setAccessible(true);
        ((CompletableFuture<?>)submit.invoke(client,(Callable<Void>)()->{attach.invoke(client,connection,false,peer,"bluetooth:AA:BB:CC:DD:EE:01",0L);return null;})).get(5,TimeUnit.SECONDS);
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory("desktop-auto-files-");terminalReceiptReplay(root.resolve("receipt-replay"));Path ar=root.resolve("a"),br=root.resolve("b");Files.createDirectories(ar);Files.createDirectories(br);
        var ai=DesktopIdentity.load(ar.resolve("identity.properties"));var bi=DesktopIdentity.load(br.resolve("identity.properties"));Events ea=new Events(),eb=new Events();
        try(DesktopStore as=new DesktopStore(ar);DesktopStore bs=new DesktopStore(br);DesktopClient a=new DesktopClient(as,ai,ea);DesktopClient b=new DesktopClient(bs,bi,eb);
            ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket ao=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort());Socket biSocket=listener.accept()){
            ea.client=a;eb.client=b;
            b.acceptConnection(bluetooth(biSocket),"bluetooth:AA:BB:CC:DD:EE:02").get(5,TimeUnit.SECONDS);outgoing(a,bluetooth(ao),bi.id());
            until(()->ready(ea)&&ready(eb),"Authenticated Bluetooth CHAT did not become ready");
            check(ea.state.listening()==null&&eb.state.listening()==null,"Test unexpectedly enabled LAN CHAT reception");
            byte[] bytes=new byte[32768*6+13];new Random(81).nextBytes(bytes);Path source=root.resolve("lan-upgrade.bin");Files.write(source,bytes);
            byte[] reverseBytes=new byte[32768*5+7];new Random(82).nextBytes(reverseBytes);Path reverse=root.resolve("duplex-return.bin");Files.write(reverse,reverseBytes);
            CompletableFuture<String> first=a.sendAttachments(bi.id(),List.of(source)),second=b.sendAttachments(ai.id(),List.of(reverse));
            first.get(15,TimeUnit.SECONDS);second.get(15,TimeUnit.SECONDS);
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.attachment()!=null&&m.attachment().state.equals("delivered")),"LAN FILE save receipt did not complete");
            until(()->bs.messages(ai.id()).stream().anyMatch(m->m.attachment()!=null&&m.attachment().outgoing&&m.attachment().state.equals("delivered")),"Reverse LAN FILE save receipt did not complete");
            check(a.transferDiagnostics(bi.id()).contains("file_transport=lan"),"Bluetooth CHAT did not select an independent ordinary LAN FILE channel");
            check(b.transferDiagnostics(ai.id()).contains("file_transport=lan"),"Receiver did not identify the actual LAN FILE channel");
            check(ea.state.transport().equals("bluetooth")&&eb.state.transport().equals("bluetooth"),"FILE upgrade changed CHAT transport");
            AttachmentRecord received=bs.messages(ai.id()).stream().filter(m->m.attachment()!=null&&!m.attachment().outgoing).findFirst().orElseThrow().attachment();
            check(Arrays.equals(bytes,Files.readAllBytes(b.attachmentPath(ai.id(),received).get(5,TimeUnit.SECONDS))),"Independent FILE lane changed bytes");
            AttachmentRecord returned=as.messages(bi.id()).stream().filter(m->m.attachment()!=null&&!m.attachment().outgoing).findFirst().orElseThrow().attachment();
            check(Arrays.equals(reverseBytes,Files.readAllBytes(a.attachmentPath(bi.id(),returned).get(5,TimeUnit.SECONDS))),"Reverse FILE lane changed bytes");
            check(a.send("CHAT remains on Bluetooth").get(5,TimeUnit.SECONDS),"CHAT send failed after FILE upgrade");
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.attachment()==null&&m.status().equals("delivered")),"Original CHAT ACK stopped after FILE upgrade");
            Path blockedFile=root.resolve("interrupted.bin");
            try(OutputStream output=Files.newOutputStream(blockedFile)){byte[] block=new byte[1024*1024];for(int n=0;n<16;n++)output.write(block);}
            BlockingSource blocked=new BlockingSource(blockedFile);
            Object session=field(a,"current");ClientAttachmentTransfers transfers=(ClientAttachmentTransfers)field(session,"transfers");
            String interrupted=transfers.offer(blocked,"interrupted.bin","application/octet-stream").get(5,TimeUnit.SECONDS);
            check(blocked.reached.await(10,TimeUnit.SECONDS),"Interrupted source never reached transfer barrier");
            ((AutomaticFileRoutes)field(session,"files")).abort();
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.id().equals(interrupted)&&m.attachment().state.equals("paused")),"FILE lane loss did not pause its active task");
            AttachmentRecord pausedLive=a.messages(bi.id()).get(5,TimeUnit.SECONDS).stream().filter(m->m.id().equals(interrupted)).findFirst().orElseThrow().attachment();
            check(pausedLive.route.equals("lan"),"Live paused feedback erased durable actual FILE channel");
            check(ready(ea)&&ready(eb),"FILE lane loss disconnected healthy Bluetooth CHAT");
            check(a.send("CHAT survives FILE loss").get(5,TimeUnit.SECONDS),"CHAT unavailable after FILE lane loss");
            try{a.attachmentAction(bi.id(),interrupted,true,"resume").get(15,TimeUnit.SECONDS);until(()->as.messages(bi.id()).stream().anyMatch(m->m.id().equals(interrupted)&&m.attachment().state.equals("delivered")),"Paused FILE did not resume after lane renegotiation");}
            catch(Exception|AssertionError failure){for(DesktopStore store:List.of(as,bs))for(DesktopStore.Message message:store.messages(store==as?bi.id():ai.id()))if(message.id().equals(interrupted))System.err.println((store==as?"sender":"receiver")+" state="+message.attachment().state+" route="+message.attachment().route+" transferred="+message.attachment().transferred);System.err.println("route terminal sender="+field(field(session,"files"),"terminal")+" receiver="+field(field(field(b,"current"),"files"),"terminal"));System.err.println("sender notice="+ea.notice+" "+a.transferDiagnostics(bi.id())+"receiver notice="+eb.notice+" "+b.transferDiagnostics(ai.id()));throw failure;}
            check(field(session,"transfers")==transfers,"FILE resume replaced immutable engine/queue owner");
            AttachmentRecord resumed=bs.messages(ai.id()).stream().filter(m->m.id().equals(interrupted)).findFirst().orElseThrow().attachment();
            check(Files.mismatch(blockedFile,b.attachmentPath(ai.id(),resumed).get(5,TimeUnit.SECONDS))==-1,"Renegotiated FILE resume changed bytes");
            AutomaticFileRoutes aroutes=(AutomaticFileRoutes)field(session,"files"),broutes=(AutomaticFileRoutes)field(field(b,"current"),"files");
            Path flowing=root.resolve("flowing.bin");try(OutputStream output=Files.newOutputStream(flowing)){byte[] block=new byte[1024*1024];for(int n=0;n<128;n++)output.write(block);}
            String flowingId;try{flowingId=a.sendAttachment(bi.id(),flowing).get(15,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(a.transferDiagnostics(bi.id())+"\nPEER\n"+b.transferDiagnostics(ai.id()),error);}
            until(()->b.liveMessages(ai.id(),bs.messages(ai.id())).stream().anyMatch(m->m.id().equals(flowingId)&&m.attachment().transferred>=1024*1024),"Unblocked FILE never started flowing");
            ((FramedSession)field(aroutes,"selected")).close(UiText.EMPTY);
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.id().equals(flowingId)&&m.attachment().state.equals("delivered")),"Unblocked socket loss did not automatically resume and deliver");
            check(ready(ea)&&ready(eb),"Active FILE socket close ended healthy CHAT");
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.id().equals(flowingId)&&m.attachment().state.equals("delivered")),"Unblocked socket-loss task could not resume on same engine");
            check(field(session,"transfers")==transfers,"Active FILE loss replaced original engine");

            aroutes.abort();broutes.abort();until(()->aroutes.route().isEmpty()&&broutes.route().isEmpty(),"Old LAN FILE route did not close");
            AutomaticFileRoutes follower=ai.id().compareTo(bi.id())<0?broutes:aroutes;
            ((AutomaticFileRoutes.Platform)field(follower,"platform")).close();
            String fallback=a.sendAttachment(bi.id(),source).get(15,TimeUnit.SECONDS);
            until(()->as.messages(bi.id()).stream().anyMatch(m->m.id().equals(fallback)&&m.attachment().state.equals("delivered")),"Unavailable ordinary LAN did not fall back to authenticated Bluetooth CHAT");
            check(aroutes.route().equals("bluetooth")&&broutes.route().equals("bluetooth"),"FILE fallback falsely retained ordinary LAN provenance");
            AttachmentRecord previousLan=bs.messages(ai.id()).stream().filter(m->m.id().equals(received.info.id)).findFirst().orElseThrow().attachment();
            check(previousLan.route.equals("lan"),"Completed FILE history followed current CHAT/fallback mode");
            a.disconnect().get(5,TimeUnit.SECONDS);until(()->!ready(ea)&&!ready(eb),"CHAT disconnect did not close bound FILE session");
            System.out.println("DesktopAutomaticFileRoutesTests: Bluetooth CHAT upgrades FILE to independent authenticated LAN, duplex bytes/save receipts, original CHAT ACK, local interruption pause, same-engine resume, honest Bluetooth fallback, durable paths and disconnect");
        }finally{try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    /** Storage replay fixture; authenticated Noise transport is exercised by the surrounding client test. */
    private static void terminalReceiptReplay(Path root)throws Exception{
        String senderId=UUID.randomUUID().toString(),receiverId=UUID.randomUUID().toString(),generation=UUID.randomUUID().toString();
        java.util.concurrent.atomic.AtomicReference<String> route=new java.util.concurrent.atomic.AtomicReference<>("lan");
        java.util.concurrent.atomic.AtomicBoolean dropped=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> problem=new java.util.concurrent.atomic.AtomicReference<>();
        AttachmentTransferV2[] engines=new AttachmentTransferV2[2];
        class Pipe implements AttachmentTransferV2.Wire{
            final int side;Pipe(int side){this.side=side;}
            public boolean send(TransferPacket packet){
                if(side==1&&packet.kind==TransferPacket.Kind.COMPLETE&&dropped.compareAndSet(false,true))return true;
                try{engines[1-side].receive(packet,true);return true;}catch(IOException failure){problem.compareAndSet(null,failure);return false;}
            }
            public void abort(){problem.compareAndSet(null,new IOException("Replay fixture pipeline aborted"));}
        }
        try(DesktopStore senderStore=new DesktopStore(root.resolve("a"));DesktopStore receiverStore=new DesktopStore(root.resolve("b"))){
            Path source=root.resolve("saved-on-lan.bin");byte[] bytes=new byte[32768*3+17];new Random(93).nextBytes(bytes);Files.write(source,bytes);
            Path outgoing=senderStore.attachmentDirectory(receiverId),incoming=receiverStore.attachmentDirectory(senderId);
            AttachmentTransferV2.Listener outgoingHistory=record->{try{senderStore.attachment(receiverId,record.withRoute(route.get()));}catch(IOException failure){problem.compareAndSet(null,failure);throw failure;}};
            AttachmentTransferV2.Listener incomingHistory=record->{try{receiverStore.attachment(senderId,record.withRoute(route.get()));}catch(IOException failure){problem.compareAndSet(null,failure);throw failure;}};
            try(AttachmentTransferV2 sender=new AttachmentTransferV2(outgoing,"a".repeat(64),"b".repeat(64),generation,false,new Pipe(0),outgoingHistory);
                AttachmentTransferV2 receiver=new AttachmentTransferV2(incoming,"b".repeat(64),"a".repeat(64),generation,false,new Pipe(1),incomingHistory)){
                engines[0]=sender;engines[1]=receiver;
                String id=sender.offer(new FileAttachmentSource(source),"saved-on-lan.bin","application/octet-stream").get(5,TimeUnit.SECONDS);
                until(()->dropped.get()&&senderStore.messages(receiverId).stream().anyMatch(m->m.id().equals(id)&&m.attachment().state.equals("awaitingReceipt")),"COMPLETE drop did not leave real sender awaiting saved receipt");
                AttachmentRecord originallySaved=receiverStore.messages(senderId).stream().filter(m->m.id().equals(id)).findFirst().orElseThrow().attachment();
                check(originallySaved.state.equals("received")&&originallySaved.route.equals("lan"),"Receiver did not durably save original LAN result before receipt loss");
                sender.pauseOnDisconnect().get(5,TimeUnit.SECONDS);receiver.pauseOnDisconnect().get(5,TimeUnit.SECONDS);route.set("bluetooth");
                TransferCheckpoint checkpoint=new TransferCheckpointStore(outgoing.resolve(".tasks-v2")).list().stream().filter(value->value.info().id.equals(id)).findFirst().orElseThrow();
                sender.resume(checkpoint.key(),new FileAttachmentSource(source,checkpoint.key().sourceGeneration())).get(5,TimeUnit.SECONDS);
                until(()->problem.get()!=null||senderStore.messages(receiverId).stream().anyMatch(m->m.id().equals(id)&&m.attachment().state.equals("delivered")),"Lost saved receipt was not replayed over new FILE route");
                check(problem.get()==null,"Saved COMPLETE checkpoint replay rejected a different current FILE route: "+problem.get());
                AttachmentRecord replayed=receiverStore.messages(senderId).stream().filter(m->m.id().equals(id)).findFirst().orElseThrow().attachment();
                check(replayed.route.equals("lan")&&replayed.equals(originallySaved),"COMPLETE replay relabeled original received bytes as Bluetooth");
                check(Files.mismatch(source,receiverStore.attachmentFile(senderId,replayed.info,false))==-1,"Saved receipt replay changed original private bytes");
                System.out.println("Desktop receipt replay: real COMPLETE checkpoint replays across LAN→Bluetooth with original saved channel/bytes retained");
            }
        }
    }
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static final class BlockingSource implements AttachmentSource {
        final FileAttachmentSource source;final CountDownLatch reached=new CountDownLatch(1),released=new CountDownLatch(1);
        BlockingSource(Path path)throws IOException{source=new FileAttachmentSource(path);}
        public long size(){return source.size();}public String generation(){return source.generation();}public boolean seekable(){return true;}
        public String persistentReference(){return source.persistentReference();}public void verifyUnchanged()throws IOException{source.verifyUnchanged();}
        public InputStream open(long offset)throws IOException{return new FilterInputStream(source.open(offset)){
            long position=offset;
            public int read(byte[] bytes,int start,int length)throws IOException{
                if(position>=1024*1024){reached.countDown();try{if(!released.await(60,TimeUnit.SECONDS))throw new IOException("Source barrier timed out");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new InterruptedIOException();}}
                int n=super.read(bytes,start,length);if(n>0)position+=n;return n;
            }
            public void close()throws IOException{released.countDown();super.close();}
        };}
        public void close()throws IOException{released.countDown();source.close();}
    }
    private static boolean ready(Events events){return events.state!=null&&events.state.phase().equals("ready");}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private interface Check{boolean test()throws Exception;}
    private static void until(Check test,String failure)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(System.nanoTime()<end){if(test.test())return;Thread.sleep(20);}throw new AssertionError(failure);}
}
