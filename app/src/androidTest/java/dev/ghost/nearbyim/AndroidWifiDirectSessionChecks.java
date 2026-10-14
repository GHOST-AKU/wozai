package dev.ghost.nearbyim;

import android.app.Instrumentation;
import android.net.Uri;
import dev.ghost.nearbyim.core.*;
import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.transport.Peer;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real controller/Noise/receipt regression. Loopback here does NOT validate P2P radio establishment. */
final class AndroidWifiDirectSessionChecks {
    private static int checks;
    static int run(Instrumentation instrumentation)throws Exception {
        checks=0;
        for(boolean incoming:new boolean[]{true,false})for(boolean requested:new boolean[]{true,false}) {
            try(Fixture fixture=new Fixture(instrumentation,incoming,requested,false)) {
                fixture.ready(requested);
                fixture.bidirectionalFiles();
                check(onMain(instrumentation,()->fixture.controller.transferDiagnostics().contains("transport=wifi_direct\n")),"Controller loses actual direct-channel diagnostic");
                AttachmentInfo info=AttachmentInfo.v2(UUID.randomUUID().toString(),"pending.bin","application/octet-stream",32,"0".repeat(64),0);
                check(onMain(instrumentation,()->fixture.controller.attachmentRoute(fixture.peer,new AttachmentRecord(info,true,"transferring",0))==TransferPath.WIFI_DIRECT),"Native approved direct session displays a guessed LAN path");
            }
        }
        try(Fixture fixture=new Fixture(instrumentation,true,true,true)) {
            await(()->onMain(instrumentation,()->!fixture.controller.error.isEmpty()),"Selected P2P identity mismatch is rejected");
            check(onMain(instrumentation,()->!fixture.controller.connected&&fixture.controller.error.key.equals("identityVerificationFailed")),"Selected GO socket bypasses recipient identity");
            check(onMain(instrumentation,()->fixture.controller.routeNotice.isEmpty()),"Identity failure silently downgrades to another path");
        }
        return checks;
    }
    private static final class Fixture implements AutoCloseable {
        final Instrumentation instrumentation;
        final ChatController controller;
        final String peer=UUID.randomUUID().toString();
        final DeviceIdentity root=DeviceIdentity.generate();
        final Path directory;
        final FramedSession remote;
        final AtomicReference<AttachmentTransferV2> engine=new AtomicReference<>();
        final BlockingQueue<AttachmentRecord> records=new LinkedBlockingQueue<>();
        final BlockingQueue<String> textAcks=new LinkedBlockingQueue<>();
        Fixture(Instrumentation instrumentation,boolean incoming,boolean requested,boolean mismatch)throws Exception {
            this.instrumentation=instrumentation;
            controller=onMain(instrumentation,()->new ChatController(instrumentation.getTargetContext(),()->{}));
            directory=Files.createTempDirectory(instrumentation.getTargetContext().getCacheDir().toPath(),"direct-noise-");
            onMain(instrumentation,()->{
                // The emulator has no P2P radio. Exercise the actual socket-delivery boundary independently.
                controller.directRunning=true;
                if(requested){Method begin=ChatController.class.getDeclaredMethod("beginConnect",int.class,String.class,boolean.class,String.class);begin.setAccessible(true);check((boolean)begin.invoke(controller,Peer.WIFI_DIRECT,mismatch?UUID.randomUUID().toString():peer,false,null),"Native requested direct session cannot begin");}
                else controller.selectConversation(new ChatStore.Conversation(peer,"Direct fixture",0));
                return null;
            });
            Socket local,far;
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {far=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());local=server.accept();}
            AtomicReference<FramedSession> session=new AtomicReference<>();byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
            remote=FramedSession.secure(connection(far),peer,"Direct fixture",root,key,incoming,new FramedSession.Listener(){
                public void onHello(Frame hello){try{
                    FramedSession wire=session.get();
                    engine.set(new AttachmentTransferV2(directory,root.fingerprint(),DeviceIdentity.fingerprint(wire.remotePublicKey()),wire.connectionGeneration(),false,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){return wire.sendTransfer(packet);}public void abort(){wire.close(UiText.of("attachmentFailed"));}},record->records.add(record)));
                    wire.approve();
                }catch(IOException failure){throw new UncheckedIOException(failure);}}
                public void onTransfer(TransferPacket packet){try{engine.get().receive(packet,true);}catch(IOException failure){throw new UncheckedIOException(failure);}}
                public void onText(Frame text){session.get().acknowledge(text.id);}
                public void onAck(String id){textAcks.add(id);}
                public void onReady(){}
                public void onClosed(UiText reason){}
            },true);
            session.set(remote);remote.start();
            onMain(instrumentation,()->{controller.onConnection(Peer.WIFI_DIRECT,connection(local),incoming);return null;});
        }
        void ready(boolean requested)throws Exception {
            if(!requested){await(()->onMain(instrumentation,()->controller.approvalId!=null),"Passive direct TCP role still requires chat consent");check(!remote.isReady(),"Passive GC automatically approves unknown contact");onMain(instrumentation,()->{controller.approve(controller.approvalId,false);return null;});}
            await(()->onMain(instrumentation,()->controller.connected&&controller.canSendAttachment()),"Both Direct socket roles create ready production file engines");
            check(onMain(instrumentation,()->controller.approvalId==null&&peer.equals(controller.selectedId)&&controller.endToEndEncrypted(peer)),"GO/GC changes selected peer or encryption");
            String id=UUID.randomUUID().toString();check(remote.send(new Frame(Frame.TEXT,id,"direct consent proof",System.currentTimeMillis())),"Direct fixture sends encrypted text");
            check(id.equals(textAcks.poll(10,TimeUnit.SECONDS)),"Native Direct controller acknowledges text before/without saving");
        }
        void bidirectionalFiles()throws Exception {
            byte[] expected=new byte[65539];new Random(927).nextBytes(expected);
            Path source=directory.resolve("source.bin");Files.write(source,expected);
            String id=engine.get().offer(new FileAttachmentSource(source),"direct-roundtrip.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            await(()->onMain(instrumentation,()->controller.messages.stream().anyMatch(message->message.id.equals(id)&&message.attachment!=null&&message.attachment.state.equals("received"))),"Direct-mode native receiver saves file");
            AttachmentRecord received=onMain(instrumentation,()->controller.messages.stream().filter(message->message.id.equals(id)).findFirst().orElseThrow().attachment);
            Path receivedFile=controller.attachmentPath(peer,received.info).get(10,TimeUnit.SECONDS);
            check(Arrays.equals(expected,Files.readAllBytes(receivedFile)),"Direct-mode receiver changes original bytes");
            Uri uri=new Uri.Builder().scheme("content").authority(instrumentation.getTargetContext().getPackageName()+".attachments").appendPath(peer).appendPath(receivedFile.getFileName().toString()).appendQueryParameter("name",received.info.name).appendQueryParameter("mime",received.info.mime).build();
            onMain(instrumentation,()->{controller.sendAttachment(peer,controller.attachmentSessionToken(),uri);return null;});
            AttachmentRecord returned=null;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline){AttachmentRecord next=records.poll(1,TimeUnit.SECONDS);if(next!=null&&!next.outgoing&&next.state.equals("received")){returned=next;break;}}
            check(returned!=null,"Direct-mode native sender never completes durable return file");
            check(Arrays.equals(expected,Files.readAllBytes(AttachmentTransfer.file(directory,returned.info,false))),"Return file bytes differ after encrypted chunks");
            String returnedId=returned.info.id;
            await(()->onMain(instrumentation,()->controller.messages.stream().anyMatch(message->message.id.equals(returnedId)&&message.attachment!=null&&message.attachment.state.equals("delivered"))),"Direct-mode sender receives durable saved receipt");
        }
        public void close()throws Exception {
            remote.close(UiText.of("connectionEnded"));
            if(engine.get()!=null)engine.get().shutdown().get(10,TimeUnit.SECONDS);
            onMain(instrumentation,()->{controller.destroy();return null;});
        }
    }
    private static StreamConnection connection(Socket socket){return new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "controller fixture, no P2P radio";}public void close()throws IOException{socket.close();}};}
    private static <T>T onMain(Instrumentation instrumentation,Callable<T> operation)throws Exception{Object[] result=new Object[2];instrumentation.runOnMainSync(()->{try{result[0]=operation.call();}catch(Throwable error){result[1]=error;}});if(result[1]!=null)throw new Exception((Throwable)result[1]);@SuppressWarnings("unchecked")T value=(T)result[0];return value;}
    private static void await(Callable<Boolean> condition,String message)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<deadline){if(condition.call())return;Thread.sleep(20);}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
