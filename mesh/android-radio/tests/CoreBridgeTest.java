package org.nearbymesh.radio;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class CoreBridgeTest {
    static void require(boolean b) {if(!b)throw new AssertionError();}
    public static void main(String[] args)throws Exception {
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            CountDownLatch sent=new CountDownLatch(1),closed=new CountDownLatch(1);
            ByteArrayOutputStream captured=new ByteArrayOutputStream();AtomicInteger closes=new AtomicInteger();
            CoreBridge bridge=new CoreBridge(listener.getLocalPort(),new CoreBridge.Sink(){
                public synchronized void send(byte[] data)throws IOException{captured.write(data);if(captured.size()>=4)sent.countDown();}
                public void close(){closes.incrementAndGet();}
            },closed::countDown);
            bridge.start();try(Socket core=listener.accept()) {
                byte[] data=new byte[4096];new Random(1).nextBytes(data);
                require(bridge.receive(data));byte[] got=new byte[data.length];new DataInputStream(core.getInputStream()).readFully(got);require(Arrays.equals(got,data));
                core.getOutputStream().write(new byte[]{0,1,2,3});require(sent.await(2,TimeUnit.SECONDS));require(Arrays.equals(captured.toByteArray(),new byte[]{0,1,2,3}));
                require(!bridge.receive(new byte[4097]));require(closed.await(2,TimeUnit.SECONDS));bridge.close();require(closes.get()==1);require(!bridge.receive(new byte[]{1}));
            }
        }
        AtomicInteger n=new AtomicInteger();CoreBridge b=new CoreBridge(1,new CoreBridge.Sink(){public void send(byte[] d){}public void close(){n.incrementAndGet();}},()->{});
        b.close();try{b.start();throw new AssertionError();}catch(IOException expected){}require(n.get()==1);
        CoreBridge bounded=new CoreBridge(1,new CoreBridge.Sink(){public void send(byte[] d){}public void close(){}},()->{});
        for(int i=0;i<8;i++)require(bounded.receive(new byte[4096]));
        require(!bounded.receive(new byte[1]));bounded.close();
        System.out.println("CoreBridgeTest: PASS");
    }
}
