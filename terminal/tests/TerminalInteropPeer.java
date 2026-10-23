package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Real shipping Java Noise, record codecs and attachment engine; no alternate wire implementation. */
public final class TerminalInteropPeer {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        boolean initiator=args[0].equals("client");Path root=Path.of(args[2]);Files.createDirectories(root);
        if(initiator){try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(args[1]))){exchange(socket,true,root);}}
        else try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(30_000);Files.writeString(Path.of(args[1]),Integer.toString(listener.getLocalPort()));
            try(Socket socket=listener.accept()){exchange(socket,false,root);}
        }
        System.out.println("PASS Java production NIM4: both root proofs, safety code, text/ACK, streaming file send/receive, SHA-256 and saved receipt");
    }
    private static void exchange(Socket socket,boolean initiator,Path root)throws Exception {
        socket.setSoTimeout(20_000);socket.setTcpNoDelay(true);
        StreamConnection connection=new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "terminal loopback fixture";}public void close()throws IOException{socket.close();}};
        DeviceIdentity identity=DeviceIdentity.generate();byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        try(NoiseRecordChannel channel=new NoiseRecordChannel(connection,initiator,identity,"11111111-2222-3333-4444-555555555555","Java 终端 👩‍💻",key,null,null,false,false)) {
            channel.establish();require(channel.verified(),"unauthenticated channel");
            String safety=new SafetyCode(identity.publicKey(),channel.remotePublicKey()).hex();
            channel.write(ProtocolV4.encode(new Frame(Frame.READY,"","",0)));
            require(ProtocolV4.decode(channel.read()).type==Frame.READY,"missing mutual consent");
            byte[] content=new byte[3*TransferLimits.BLOCK_BYTES+17];for(int i=0;i<content.length;i++)content[i]=(byte)(i*31+7);Path source=root.resolve("java-source.bin");Files.write(source,content);
            BlockingQueue<AttachmentRecord> notices=new LinkedBlockingQueue<>();
            try(AttachmentTransferV2 files=new AttachmentTransferV2(root.resolve("received"),identity.fingerprint(),DeviceIdentity.fingerprint(channel.remotePublicKey()),channel.connectionGeneration(),false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket p){try{channel.write(ProtocolV4.encodeTransfer(p));return true;}catch(IOException e){return false;}}
                public void abort(){try{channel.close();}catch(Exception ignored){}}
            },notices::add)) {
                files.offer(new FileAttachmentSource(source),"来自Java.bin","application/octet-stream").get(5,TimeUnit.SECONDS);
                String textId=UUID.randomUUID().toString().toUpperCase(Locale.ROOT),whiteId=UUID.randomUUID().toString();
                channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,textId,"Java 你好 👩‍💻\n"+safety,1234)));
                channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,whiteId,"\u00a0\u2003",1235)));
                channel.write(ProtocolV4.encode(new Frame(Frame.PING,"","",0)));
                boolean text=false,ack=false,white=false,pong=false,received=false,delivered=false;
                for(int records=0;records<1000&&!(text&&ack&&white&&pong&&received&&delivered);records++) {
                    ProtocolV4.Record record=ProtocolV4.decodeRecord(channel.read());
                    if(record.transfer!=null){files.receive(record.transfer,true);}
                    else switch(record.frame.type) {
                        case Frame.TEXT:require(record.frame.body.equals("Rust 你好 👩‍💻\n"+safety),"text or safety mismatch");text=true;channel.write(ProtocolV4.encode(new Frame(Frame.ACK,record.frame.id,"",0)));break;
                        case Frame.ACK:if(record.frame.id.equals(textId))ack=true;else{require(record.frame.id.equals(whiteId),"unexpected ACK");white=true;}break;
                        case Frame.PING:channel.write(ProtocolV4.encode(new Frame(Frame.PONG,"","",0)));break;
                        case Frame.PONG:pong=true;break;
                        default:throw new AssertionError("unexpected frame "+record.frame.type);
                    }
                    AttachmentRecord notice;while((notice=notices.poll())!=null){
                        require(!notice.state.equals("failed"),"Java attachment engine failed");
                        if(notice.state.equals("received")){require(Arrays.equals(content,Files.readAllBytes(AttachmentTransfer.file(root.resolve("received"),notice.info))),"received bytes differ");received=true;}
                        if(notice.state.equals("delivered"))delivered=true;
                    }
                    // The engine can complete asynchronously after the final incoming record.
                    if(text&&ack&&white&&pong) {
                        for(int n=0;n<4&&!(received&&delivered);n++) {
                            notice=notices.poll(20,TimeUnit.MILLISECONDS);if(notice==null)break;
                            if(notice.state.equals("received")){require(Arrays.equals(content,Files.readAllBytes(AttachmentTransfer.file(root.resolve("received"),notice.info))),"received bytes differ");received=true;}
                            if(notice.state.equals("delivered"))delivered=true;
                        }
                    }
                }
                require(text&&ack&&white&&pong&&received&&delivered,"incomplete exchange");
                channel.write(ProtocolV4.encode(new Frame(Frame.BYE,"","",0)));
            }
        }
    }
}
