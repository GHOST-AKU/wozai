package dev.ghost.nearbyim.core;
import dev.ghost.nearbyim.i18n.UiText;
import java.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Full production FramedSession peer, rather than a fixture-specific capability implementation. */
public final class TerminalLongTextPeer implements FramedSession.Listener {
    private static final String BODY="中".repeat(10922)+"ab";
    private final CountDownLatch received=new CountDownLatch(1),receipt=new CountDownLatch(1);
    private final String id=UUID.randomUUID().toString();
    private FramedSession wire;private boolean sent;
    public static void main(String[] args)throws Exception{
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
            server.setSoTimeout(15000);Files.writeString(Path.of(args[0]),Integer.toString(server.getLocalPort()));
            Socket socket=server.accept();TerminalLongTextPeer peer=new TerminalLongTextPeer();
            StreamConnection connection=new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "long text fixture";}public void close()throws IOException{socket.close();}};
            peer.wire=FramedSession.secure(connection,UUID.randomUUID().toString(),"Java long text",DeviceIdentity.generate(),new byte[32],false,peer);
            try{peer.wire.start();if(!peer.received.await(15,TimeUnit.SECONDS)||!peer.receipt.await(5,TimeUnit.SECONDS))throw new AssertionError("Incomplete 32KiB exchange");}
            finally{peer.wire.close(UiText.EMPTY);}
        }
    }
    public void onHello(Frame frame){wire.approve();}public void onReady(){}
    public void onTextLimit(int bytes){maybeSend();}
    private void maybeSend(){if(!sent&&received.getCount()==0&&wire.textLimit()==32768){sent=true;if(!wire.send(new Frame(Frame.TEXT,id,BODY,1)))throw new AssertionError("32KiB send rejected");}}
    public void onText(Frame frame){if(!frame.body.equals(BODY))throw new AssertionError("Long text altered");wire.acknowledge(frame.id);received.countDown();maybeSend();}
    public void onAck(String saved){if(saved.equals(id))receipt.countDown();}public void onClosed(UiText reason){}
}
