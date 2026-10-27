package dev.ghost.nearbyim.core;
import dev.ghost.nearbyim.i18n.UiText;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
public final class LegacyTextInteropTests {
    public static void main(String[] args)throws Exception{
        try(URLClassLoader loader=new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},ClassLoader.getPlatformClassLoader())){
            Class<?> type=loader.loadClass("dev.ghost.nearbyim.core.LegacyTextPeer");
            for(boolean initiator:new boolean[]{false,true})try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){
                Socket first=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),second=server.accept();
                Object old=type.getConstructor(Socket.class,boolean.class).newInstance(second,!initiator);
                TextCapacityTests.End current=new TextCapacityTests.End();
                current.wire=FramedSession.secure(SessionTests.connection(first),CoreTests.A,"new",DeviceIdentity.generate(),new byte[32],initiator,current);
                try{
                    type.getMethod("start").invoke(old);current.wire.start();
                    TextCapacityTests.check(current.hello.await(5,TimeUnit.SECONDS),"new hello");current.wire.approve();
                    TextCapacityTests.check((Boolean)type.getMethod("ready").invoke(old),"legacy ready");
                    TextCapacityTests.check(current.ready.await(5,TimeUnit.SECONDS),"new ready");
                    String text="🙂".repeat(2048),id=UUID.randomUUID().toString();
                    TextCapacityTests.check(current.wire.send(new Frame(Frame.TEXT,id,text,1)),"8KiB new-to-old rejected");
                    TextCapacityTests.check(text.equals(type.getMethod("receive").invoke(old))&&id.equals(current.ack.poll(5,TimeUnit.SECONDS)),"old peer disconnected or text changed after probe");
                    TextCapacityTests.check(current.wire.textLimit()==8192&&!current.wire.send(new Frame(Frame.TEXT,UUID.randomUUID().toString(),text+"a",1)),"silent old peer accepted larger text");
                    TextCapacityTests.check((Boolean)type.getMethod("send",String.class).invoke(old,text),"8KiB old-to-new rejected");
                    TextCapacityTests.check(text.equals(current.text.poll(5,TimeUnit.SECONDS).body)&&current.wire.isReady(),"old-to-new text lost");
                }finally{current.wire.close(UiText.EMPTY);type.getMethod("close").invoke(old);}
            }
        }
        System.out.println("Pinned old NIM4 text: both handshake roles, ignored PONG probe, bidirectional 8KiB, explicit oversized rejection passed");
    }
}
