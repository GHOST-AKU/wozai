package dev.ghost.wozai;

import dev.ghost.nearbyim.i18n.UiText;

import dev.ghost.nearbyim.core.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Verifies Bluetooth routes share the real signed NIM2 consent/storage/ACK pipeline. */
public final class TransportTests {
    private static StreamConnection wrap(Socket socket,String label) { return new StreamConnection() {
        public InputStream input() throws IOException { return socket.getInputStream(); }
        public OutputStream output() throws IOException { return socket.getOutputStream(); }
        public String label() { return label; }
        public void close() throws IOException { socket.close(); }
    }; }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("wozai-transport-test"); String peerId=UUID.randomUUID().toString();
        BlockingQueue<DesktopClient.Request> requests=new LinkedBlockingQueue<>(); BlockingQueue<DesktopClient.State> states=new LinkedBlockingQueue<>(); BlockingQueue<String> acks=new LinkedBlockingQueue<>();
        try(var store=new DesktopStore(root); var client=new DesktopClient(store,DesktopIdentity.load(root.resolve("identity.properties")),new DesktopClient.Listener() {
            public void changed(DesktopClient.State s) { states.add(s); } public void request(DesktopClient.Request r) { requests.add(r); } public void notice(UiText text) { }
        }); var listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress()); var outgoing=new Socket(InetAddress.getLoopbackAddress(),listener.getLocalPort()); var incoming=listener.accept()) {
            CountDownLatch ready=new CountDownLatch(1), hello=new CountDownLatch(1); var remoteIdentity=DeviceIdentity.generate();
            FramedSession remote=new FramedSession(wrap(outgoing,"Phone"),peerId,"Android Bluetooth",remoteIdentity,new FramedSession.Listener() {
                public void onHello(Frame f) { hello.countDown(); } public void onReady() { ready.countDown(); } public void onText(Frame f) { } public void onAck(String id) { acks.add(id); } public void onClosed(UiText why) { }
            });
            try {
                client.acceptConnection(wrap(incoming,"Bluetooth"),"bluetooth:AA:BB:CC:DD:EE:01").get(2,TimeUnit.SECONDS); remote.start();
                var request=requests.poll(3,TimeUnit.SECONDS); if(request==null||!request.publicKey().equals(remoteIdentity.publicKey()))throw new AssertionError("Bluetooth route skipped identity/consent");
                if(store.peer(peerId)!=null)throw new AssertionError("Premature trust persistence");
                client.approve(request,true).get(2,TimeUnit.SECONDS); if(!hello.await(3,TimeUnit.SECONDS))throw new AssertionError("Missing verified remote greeting"); remote.approve(); if(!ready.await(3,TimeUnit.SECONDS))throw new AssertionError("Bluetooth route never ready");
                DesktopClient.State state; do { state=states.poll(3,TimeUnit.SECONDS); } while(state!=null&&!state.phase().equals("ready"));
                if(state==null||!state.transport().equals("bluetooth")||!state.peer().endpoint().equals("bluetooth:AA:BB:CC:DD:EE:01"))throw new AssertionError("Actual transport or reconnect route lost");
                String id=UUID.randomUUID().toString(); remote.send(new Frame(Frame.TEXT,id,"蓝牙消息 🙂",System.currentTimeMillis()));
                if(!id.equals(acks.poll(3,TimeUnit.SECONDS))||!store.messages(peerId).get(0).body().equals("蓝牙消息 🙂"))throw new AssertionError("Bluetooth message ACK before persistence or missing");
                client.revoke(peerId).get(2,TimeUnit.SECONDS); if(!store.peer(peerId).publicKey().isEmpty()||store.messages(peerId).size()!=1)throw new AssertionError("Revoke must preserve chat and remove trust");
                System.out.println("TransportTests: shared identity, consent, Bluetooth route, saved-message ACK and revoke passed (byte-stream simulation; no physical radio)");
            } finally { remote.close(UiText.EMPTY); }
        } finally { try(var paths=Files.walk(root)) { for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p); } }
    }
}
