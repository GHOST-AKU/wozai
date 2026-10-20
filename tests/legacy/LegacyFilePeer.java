package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import dev.ghost.nearbyim.i18n.UiText;

/** Compiled against a pinned pre-extension tree in a separate class loader. */
public final class LegacyFilePeer implements FramedSession.Listener,AutoCloseable {
    static final String A="11111111-1111-4111-8111-111111111111",B="22222222-2222-4222-8222-222222222222";
    final DeviceIdentity identity=DeviceIdentity.generate();final byte[] key=new byte[32];final String expected;
    final FramedSession wire;volatile AutomaticFileRoutes routes;final CountDownLatch ready=new CountDownLatch(1);
    final BlockingQueue<TransferPacket> received=new LinkedBlockingQueue<>();final BlockingQueue<IOException> errors=new LinkedBlockingQueue<>();
    public LegacyFilePeer(Socket socket,String expected)throws Exception{
        this.expected=expected;new java.security.SecureRandom().nextBytes(key);
        StreamConnection connection=new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "legacy";}public void close()throws IOException{socket.close();}};
        wire=FramedSession.secure(connection,B,"legacy",identity,key,false,this,false,true);
    }
    public String publicKey(){return identity.publicKey();}
    public void start(){wire.start();}
    public boolean ready()throws Exception{return ready.await(5,TimeUnit.SECONDS);}
    public void onHello(Frame frame){if(!A.equals(frame.id)||!expected.equals(wire.remotePublicKey()))throw new IllegalStateException("Wrong current peer");wire.approve();}
    public void onReady(){routes=new AutomaticFileRoutes(wire,B,"legacy",identity,key,A,expected,"bluetooth",()->true,new AutomaticFileRoutes.Platform(){
        public boolean directSupported(){return false;}
        public void prepareLan(java.util.function.Consumer<List<AutomaticFileRoutes.Endpoint>> endpoints,java.util.function.Consumer<StreamConnection> accepted){endpoints.accept(List.of());}
        public void connectLan(List<AutomaticFileRoutes.Endpoint> endpoints,java.util.function.Consumer<StreamConnection> opened,java.util.function.Consumer<Throwable> failed){failed.accept(new IOException("No LAN"));}
        public void openDirect(boolean dialer,java.util.function.Consumer<AutomaticFileRoutes.Opened> opened,java.util.function.Consumer<Throwable> failed){failed.accept(new IOException("No Direct"));}
        public void stopDirect(){}public void close(){}
    },new AutomaticFileRoutes.Listener(){public void receive(TransferPacket packet){received.add(packet);}public void changed(){}public void failed(IOException error){errors.add(error);}});routes.start();ready.countDown();}
    public void onFileRoute(FileRoutePacket packet){routes.receiveControl(packet);}
    public void onTransfer(TransferPacket packet){routes.receiveChatTransfer(packet);}
    public void onText(Frame frame){}public void onAck(String id){}public void onClosed(UiText reason){}
    public boolean receive()throws Exception{return received.poll(5,TimeUnit.SECONDS)!=null&&errors.isEmpty();}
    public boolean send()throws Exception{return routes.ensureReady().get(5,TimeUnit.SECONDS)==AutomaticFileRoutes.Route.BLUETOOTH&&routes.send(TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),wire.connectionGeneration(),1,0,new byte[]{2}));}
    public void close(){if(routes!=null)routes.close();wire.close(UiText.EMPTY);}
}
