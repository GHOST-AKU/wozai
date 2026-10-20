package dev.ghost.nearbyim.core;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class SecureSessionTests {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args)throws Exception {
        try(Pair p=new Pair()) {
            check(p.a.hello.await(5,TimeUnit.SECONDS)&&p.b.hello.await(5,TimeUnit.SECONDS),"Secure session greeting failed");
            check(!encrypted(p.a.wire),"Encryption indicator appears before both peers approve");
            check(!p.a.wire.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"early",1)),"Text sent before consent");
            p.a.wire.approve();Thread.sleep(30);check(!p.a.wire.isReady()&&!p.b.wire.isReady(),"Single-sided consent permits data");p.b.wire.approve();
            check(p.a.ready.await(5,TimeUnit.SECONDS)&&p.b.ready.await(5,TimeUnit.SECONDS),"Secure session ready failed");
            check(encrypted(p.a.wire)&&encrypted(p.b.wire),"Authenticated Noise chat has no verified encryption indicator");
            check(p.a.wire.attachmentsV2()&&p.a.wire.attachmentSizeLimit()==TransferLimits.MAX_FILE_BYTES,"Encrypted file capability missing");
            check(p.a.wire.connectionGeneration().equals(p.b.wire.connectionGeneration()),"Connection generations disagree");
            String id=UUID.randomUUID().toString();check(p.a.wire.send(new Frame(Frame.TEXT,id,"secure 🙂",1)),"Secure text rejected");
            check(p.b.text.poll(5,TimeUnit.SECONDS).body.equals("secure 🙂")&&id.equals(p.a.ack.poll(5,TimeUnit.SECONDS)),"Saved-text receipt changed");
            String generation=UUID.randomUUID().toString();TransferPacket packet=TransferPacket.data(UUID.randomUUID().toString(),generation,p.a.wire.connectionGeneration(),5L*1024*1024*1024,4L*1024*1024*1024,new byte[]{5,4,3,2,1});
            check(p.a.wire.sendTransfer(packet),"Encrypted file packet rejected");TransferPacket received=p.b.packets.poll(5,TimeUnit.SECONDS);
            check(received!=null&&received.offset==packet.offset&&Arrays.equals(received.data,packet.data),"Encrypted packet lost long offsets/data");
            check(!p.a.wire.sendAttachment(new Frame(Frame.FILE_CANCEL,packet.transferId,"canceled",1)),"Encrypted session accepts legacy file records");
            p.a.wire.close(UiText.EMPTY);check(!encrypted(p.a.wire),"Closed session still claims live encryption");
        }
        earlyRawPeer();legacyPeer();
        System.out.println("Secure sessions: "+checks+" consent/capability/packet checks passed");
    }
    private static boolean encrypted(FramedSession wire)throws Exception {try{return (Boolean)FramedSession.class.getMethod("endToEndEncrypted").invoke(wire);}catch(NoSuchMethodException absent){return false;}}
    private static void earlyRawPeer()throws Exception {
        DeviceIdentity root=DeviceIdentity.generate(),other=DeviceIdentity.generate();End end=new End();Socket[] sockets=sockets();
        end.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",root,randomKey(),true,end);
        try(NoiseRecordChannel raw=new NoiseRecordChannel(SessionTests.connection(sockets[1]),false,other,CoreTests.B,"B",randomKey(),CoreTests.A,null)) {
            end.wire.start();raw.establish();check(end.hello.await(3,TimeUnit.SECONDS),"Raw peer handshake failed");
            raw.write(ProtocolV4.encode(new Frame(Frame.TEXT,UUID.randomUUID().toString(),"before approval",1)));
            check(end.closed.await(3,TimeUnit.SECONDS)&&end.text.isEmpty(),"Data before consent reached persistence");
        }finally{end.wire.close(UiText.EMPTY);}
    }
    private static void legacyPeer()throws Exception {
        End a=new End(),b=new End();Socket[] sockets=sockets();
        a.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,a);
        b.wire=new FramedSession(SessionTests.connection(sockets[1]),CoreTests.B,"B",DeviceIdentity.generate(),b);
        try {a.wire.start();b.wire.start();check(a.closed.await(3,TimeUnit.SECONDS)&&a.hello.getCount()==1,"NIM3 fell back to a plaintext session");check(!encrypted(b.wire),"Legacy plaintext session claims encryption");}
        finally{a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);}
    }
    private static byte[] randomKey(){byte[] key=new byte[32];new java.security.SecureRandom().nextBytes(key);return key;}
    private static Socket[] sockets()throws IOException {try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){Socket a=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),b=server.accept();a.setTcpNoDelay(true);b.setTcpNoDelay(true);return new Socket[]{a,b};}}
    private static final class End implements FramedSession.Listener {
        final CountDownLatch hello=new CountDownLatch(1),ready=new CountDownLatch(1),closed=new CountDownLatch(1);
        final BlockingQueue<Frame> text=new LinkedBlockingQueue<>();final BlockingQueue<String> ack=new LinkedBlockingQueue<>();final BlockingQueue<TransferPacket> packets=new LinkedBlockingQueue<>();
        FramedSession wire;
        public void onHello(Frame frame){hello.countDown();}public void onReady(){ready.countDown();}
        public void onText(Frame frame){text.add(frame);wire.acknowledge(frame.id);}public void onAck(String id){ack.add(id);}
        public void onTransfer(TransferPacket packet){packets.add(packet);}public void onClosed(UiText reason){closed.countDown();}
    }
    private static final class Pair implements AutoCloseable {
        final End a=new End(),b=new End();
        Pair()throws Exception {Socket[] sockets=sockets();a.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,a);b.wire=FramedSession.secure(SessionTests.connection(sockets[1]),CoreTests.B,"B",DeviceIdentity.generate(),randomKey(),false,b);a.wire.start();b.wire.start();}
        public void close(){a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);}
    }
}
