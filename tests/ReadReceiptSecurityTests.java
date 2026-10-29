package dev.ghost.nearbyim.core;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import dev.ghost.nearbyim.i18n.UiText;
import java.net.*;
import java.util.concurrent.*;
public final class ReadReceiptSecurityTests {
    public static void main(String[] args)throws Exception {reject(false,false);reject(false,true);reject(true,true);System.out.println("ReadReceiptSecurityTests: pre-consent, unnegotiated and FILE-lane injection rejected");}
    private static void reject(boolean fileLane,boolean approve)throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket rawSocket=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket localSocket=server.accept()){
            CountDownLatch hello=new CountDownLatch(1),ready=new CountDownLatch(1),closed=new CountDownLatch(1);FramedSession[] ref=new FramedSession[1];
            FramedSession.Listener listener=new FramedSession.Listener(){
                public void onHello(Frame f){hello.countDown();if(approve)ref[0].approve();}public void onReady(){ready.countDown();}
                public void onText(Frame f){}public void onAck(String id){}public void onClosed(UiText reason){closed.countDown();}
                public void onReadReceipt(ReadReceiptPacket packet){throw new AssertionError("Unapproved receipt reached persistence");}
            };
            ref[0]=FramedSession.secure(SessionTests.connection(localSocket),CoreTests.A,"local",DeviceIdentity.generate(),key(),true,listener);
            if(fileLane)ref[0].restrictToFileLane();
            try(NoiseRecordChannel raw=new NoiseRecordChannel(SessionTests.connection(rawSocket),false,DeviceIdentity.generate(),CoreTests.B,"remote",key(),null,null)){
                ref[0].start();raw.establish();if(!hello.await(3,TimeUnit.SECONDS))throw new AssertionError("Secure greeting missing");
                if(approve){raw.write(ProtocolV4.encode(new Frame(Frame.READY,"","",0)));if(!ready.await(3,TimeUnit.SECONDS))throw new AssertionError("Consent missing");}
                raw.write(ProtocolV4.encode(new Frame(Frame.READ_RECEIPT,"","",0,0,ReadReceiptPacket.policy(1,true).encode())));
                if(!closed.await(3,TimeUnit.SECONDS))throw new AssertionError("Unsafe read receipt accepted");
            }finally{ref[0].close(UiText.EMPTY);}
        }
    }
    private static byte[] key(){byte[] b=new byte[32];new java.security.SecureRandom().nextBytes(b);return b;}
}
