package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import dev.ghost.nearbyim.i18n.UiText;
/** New and pinned old Noise peers must exchange files without seeing extension records. */
public final class LegacyFileLinkInteropTests {
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Supply the separately compiled legacy classes");
        try(URLClassLoader loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader());ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            Socket outbound=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),inbound=server.accept();
            DeviceIdentity identity=DeviceIdentity.generate();byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);
            Class<?> old=loader.loadClass("dev.ghost.nearbyim.core.LegacyFilePeer");Object peer=old.getConstructor(Socket.class,String.class).newInstance(inbound,identity.publicKey());
            String remote=(String)old.getMethod("publicKey").invoke(peer);FramedSession[] wire=new FramedSession[1];AutomaticFileRoutes[] routes=new AutomaticFileRoutes[1];CountDownLatch ready=new CountDownLatch(1);BlockingQueue<TransferPacket> received=new LinkedBlockingQueue<>();
            wire[0]=FramedSession.secure(SessionTests.connection(outbound),"11111111-1111-4111-8111-111111111111","current",identity,key,true,new FramedSession.Listener(){
                public void onHello(Frame frame){wire[0].approve();}public void onReady(){ready.countDown();}public void onText(Frame frame){}public void onAck(String id){}public void onClosed(UiText reason){}
                public void onFileRoute(FileRoutePacket packet){routes[0].receiveControl(packet);}public void onFileLink(FileLinkPacket packet){routes[0].receiveFileLink(packet);}
                public void onTransfer(TransferPacket packet){routes[0].receiveChatTransfer(packet);}
            },false,true);
            old.getMethod("start").invoke(peer);wire[0].start();check(ready.await(5,TimeUnit.SECONDS)&&(Boolean)old.getMethod("ready").invoke(peer),"New/old Noise handshake failed");
            var platform=new AutomaticFileRoutesTests.TestPlatform(false,false);
            routes[0]=new AutomaticFileRoutes(wire[0],"11111111-1111-4111-8111-111111111111","current",identity,key,"22222222-2222-4222-8222-222222222222",remote,"bluetooth",()->true,platform,new AutomaticFileRoutes.Listener(){public void receive(TransferPacket packet){received.add(packet);}public void changed(){}public void failed(IOException error){throw new AssertionError(error);}});
            Path directory=Files.createTempDirectory("legacy-file-link");AttachmentTransferV2 engine=new AttachmentTransferV2(directory,identity.fingerprint(),DeviceIdentity.fingerprint(remote),wire[0].connectionGeneration(),true,new AttachmentTransferV2.Wire(){public boolean send(TransferPacket packet){return false;}public void abort(){}},record->{});
            ClientAttachmentTransfers files=new ClientAttachmentTransfers(directory,engine,checkpoint->{throw new IOException("No test source");});routes[0].attachFiles(files);
            try{
                check(routes[0].ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH,"Old peer lost its existing route");
                check(routes[0].send(TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),wire[0].connectionGeneration(),1,0,new byte[]{1})),"New to old file frame rejected");
                check((Boolean)old.getMethod("receive").invoke(peer),"Old peer failed after safe capability challenge");
                check((Boolean)old.getMethod("send").invoke(peer)&&received.poll(5,TimeUnit.SECONDS)!=null,"Old to new file frame rejected");
                check(routes[0].diagnostics().contains("file_handover_supported=false"),"Silent old peer incorrectly authorized new extension records");
                System.out.println("Pinned legacy Noise interoperability: safe ignored challenge, old selection, bidirectional FILE frames passed");
            }finally{routes[0].close();wire[0].close(UiText.EMPTY);old.getMethod("close").invoke(peer);files.shutdown().get(5,TimeUnit.SECONDS);try(var paths=Files.walk(directory)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
        }
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
