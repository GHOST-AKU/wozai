package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import dev.ghost.nearbyim.i18n.UiText;

/** Locally closed sockets cannot turn a concurrent decoded record into an identity failure. */
public final class SessionCloseRaceTests {
    public static void main(String[] args)throws Exception{
        StreamConnection[] sockets=AutomaticFileRoutesTests.sockets();FramedSession[] wires=new FramedSession[2];
        CountDownLatch ready=new CountDownLatch(2),closed=new CountDownLatch(1),failed=new CountDownLatch(1);AtomicReference<IOException> failure=new AtomicReference<>();
        StreamConnection input=new StreamConnection(){public InputStream input()throws IOException{return new FilterInputStream(sockets[1].input()){
            public int read(byte[] bytes,int offset,int length)throws IOException{int n=super.read(bytes,offset,length);if(n>30000)wires[1].close(UiText.EMPTY);return n;}
        };}public OutputStream output()throws IOException{return sockets[1].output();}public String label(){return "close-race";}public void close()throws IOException{sockets[1].close();}};
        for(int i=0;i<2;i++){final int local=i;wires[i]=FramedSession.secure(i==0?sockets[0]:input,i==0?CoreTests.A:CoreTests.B,"race",DeviceIdentity.generate(),AutomaticFileRoutesTests.key(),i==0,new FramedSession.Listener(){
            public void onHello(Frame hello){wires[local].approve();}public void onReady(){ready.countDown();}public void onText(Frame frame){}public void onAck(String id){}public void onTransfer(TransferPacket packet){}
            public void onFailure(IOException error){if(local==1){failure.set(error);failed.countDown();}}
            public void onClosed(UiText reason){if(local==1)closed.countDown();}
        },false,true);wires[i].restrictToFileLane();}
        try{
            wires[0].start();wires[1].start();if(!ready.await(5,TimeUnit.SECONDS))throw new AssertionError("Race fixture did not authenticate");
            wires[0].sendTransfer(TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),wires[0].connectionGeneration(),32768,0,new byte[32768]));
            if(!closed.await(5,TimeUnit.SECONDS))throw new AssertionError("Encrypted payload did not reach the local-close barrier");
            if(failed.await(500,TimeUnit.MILLISECONDS))throw new AssertionError("Local close reported an authentication/protocol failure",failure.get());
            System.out.println("Session local-close race: in-flight encrypted payload is discarded without a false security failure");
        }finally{for(FramedSession wire:wires)wire.close(UiText.EMPTY);}
    }
}
