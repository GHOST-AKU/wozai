package dev.ghost.nearbyim.core;
import dev.ghost.nearbyim.i18n.UiText;
import java.net.Socket;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
/** Built against the pinned released decoder, never against the new extension. */
public final class LegacyTextPeer implements FramedSession.Listener,AutoCloseable {
    private final FramedSession wire;
    private final CountDownLatch ready=new CountDownLatch(1);
    private final BlockingQueue<String> texts=new LinkedBlockingQueue<>(),receipts=new LinkedBlockingQueue<>();
    public LegacyTextPeer(Socket socket,boolean initiator)throws Exception{
        StreamConnection connection=new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "legacy text";}public void close()throws IOException{socket.close();}};
        wire=FramedSession.secure(connection,"22222222-2222-4222-8222-222222222222","old",DeviceIdentity.generate(),new byte[32],initiator,this);
    }
    public void start(){wire.start();} public boolean ready()throws Exception{return ready.await(5,TimeUnit.SECONDS);}
    public void onHello(Frame frame){wire.approve();}public void onReady(){ready.countDown();}
    public void onText(Frame frame){texts.add(frame.body);wire.acknowledge(frame.id);}public void onAck(String id){receipts.add(id);}public void onClosed(UiText reason){}
    public String receive()throws Exception{return texts.poll(5,TimeUnit.SECONDS);}
    public boolean send(String body)throws Exception{String id=UUID.randomUUID().toString();return wire.send(new Frame(Frame.TEXT,id,body,1))&&id.equals(receipts.poll(5,TimeUnit.SECONDS));}
    public void close(){wire.close(UiText.EMPTY);}
}
