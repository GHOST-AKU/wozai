package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the real encrypted writer, decoder, receipts and consent gate. */
public final class TextCapacityTests {
    public static void main(String[] args) throws Exception {
        String[] bodies={"a".repeat(32768),"中".repeat(10922)+"ab", "👩🏽‍💻e\u0301".repeat(1800)};
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            Socket outbound=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),inbound=server.accept();
            End a=new End(), b=new End();
            a.wire=FramedSession.secure(SessionTests.connection(outbound),CoreTests.A,"A",DeviceIdentity.generate(),new byte[32],true,a);
            b.wire=FramedSession.secure(SessionTests.connection(inbound),CoreTests.B,"B",DeviceIdentity.generate(),new byte[32],false,b);
            try {
                a.wire.start();b.wire.start();
                check(a.hello.await(5,TimeUnit.SECONDS)&&b.hello.await(5,TimeUnit.SECONDS),"handshake");
                a.wire.approve();
                check(!a.wire.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),bodies[0],1)),"consent bypass");
                b.wire.approve();
                check(a.expanded.await(5,TimeUnit.SECONDS)&&b.expanded.await(5,TimeUnit.SECONDS),"New peers did not negotiate a larger text capacity");
                for(String body:bodies) {
                    roundTrip(a,b,body); roundTrip(b,a,body);
                }
                check(!a.wire.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"x".repeat(32769),1)),"oversized send queued");
                check(!a.wire.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"bad\ud800",1)),"malformed Unicode queued");
                roundTrip(a,b,"still connected");
            } finally { a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY); }
        }
        System.out.println("Text capacity: mutual consent, 32KiB bidirectional Unicode, receipts, oversized/malformed rejection passed");
    }
    private static void roundTrip(End from,End to,String body)throws Exception {
        String id=UUID.randomUUID().toString();
        check(from.wire.send(new Frame(Frame.TEXT,id,body,1)),"valid text rejected");
        Frame saved=to.text.poll(5,TimeUnit.SECONDS);
        check(saved!=null&&saved.id.equals(id)&&saved.body.equals(body),"text altered or lost");
        check(id.equals(from.ack.poll(5,TimeUnit.SECONDS)),"saved receipt lost");
    }
    static final class End implements FramedSession.Listener {
        FramedSession wire;
        final CountDownLatch hello=new CountDownLatch(1), ready=new CountDownLatch(1), expanded=new CountDownLatch(1);
        final BlockingQueue<Frame> text=new LinkedBlockingQueue<>();
        final BlockingQueue<String> ack=new LinkedBlockingQueue<>();
        public void onHello(Frame f){hello.countDown();} public void onReady(){ready.countDown();}
        public void onTextLimit(int bytes){if(bytes==32768)expanded.countDown();}
        public void onText(Frame f){text.add(f);wire.acknowledge(f.id);}
        public void onAck(String id){ack.add(id);} public void onClosed(UiText reason){}
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
