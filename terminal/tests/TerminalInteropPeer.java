package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Real shipping Java Noise, record codecs and attachment engine; no alternate wire implementation. */
public final class TerminalInteropPeer {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void pngChunk(DataOutputStream out,String name,byte[] bytes)throws IOException {
        byte[] type=name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(bytes.length);out.write(type);out.write(bytes);CRC32 crc=new CRC32();crc.update(type);crc.update(bytes);out.writeInt((int)crc.getValue());
    }
    private static byte[] avatarPng()throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});
        ByteArrayOutputStream header=new ByteArrayOutputStream();DataOutputStream h=new DataOutputStream(header);h.writeInt(96);h.writeInt(96);h.write(new byte[]{8,6,0,0,0});pngChunk(out,"IHDR",header.toByteArray());
        ByteArrayOutputStream pixels=new ByteArrayOutputStream();Random random=new Random(37);try(DeflaterOutputStream deflate=new DeflaterOutputStream(pixels)){for(int y=0;y<96;y++){deflate.write(0);byte[] row=new byte[96*4];random.nextBytes(row);deflate.write(row);}}
        pngChunk(out,"IDAT",pixels.toByteArray());pngChunk(out,"IEND",new byte[0]);return bytes.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        boolean initiator=args[0].equals("client");Path root=Path.of(args[2]);Files.createDirectories(root);
        if(initiator){try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(args[1]))){exchange(socket,true,root);}}
        else try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(30_000);Path ready=Path.of(args[1]),temporary=ready.resolveSibling(ready.getFileName()+".tmp");Files.writeString(temporary,Integer.toString(listener.getLocalPort()));Files.move(temporary,ready,StandardCopyOption.ATOMIC_MOVE);
            try(Socket socket=listener.accept()){exchange(socket,false,root);}
        }
        System.out.println("PASS Java production NIM4: both root proofs, safety code, text/ACK, streaming files, saved receipts and bidirectional identity-bound avatar-v1");
    }
    private static void exchange(Socket socket,boolean initiator,Path root)throws Exception {
        socket.setSoTimeout(20_000);socket.setTcpNoDelay(true);
        StreamConnection connection=new StreamConnection(){public InputStream input()throws IOException{return socket.getInputStream();}public OutputStream output()throws IOException{return socket.getOutputStream();}public String label(){return "terminal loopback fixture";}public void close()throws IOException{socket.close();}};
        DeviceIdentity identity=DeviceIdentity.generate();byte[] key=new byte[32];new SecureRandom().nextBytes(key);
        try(NoiseRecordChannel channel=new NoiseRecordChannel(connection,initiator,identity,"11111111-2222-3333-4444-555555555555","Java 终端 👩‍💻",key,null,null,true,false)) {
            channel.establish();require(channel.verified(),"unauthenticated channel");
            String safety=new SafetyCode(identity.publicKey(),channel.remotePublicKey()).hex();
            channel.write(ProtocolV4.encode(new Frame(Frame.READY,"","",0)));
            require(ProtocolV4.decode(channel.read()).type==Frame.READY,"missing mutual consent");
            require(channel.avatarsSupported(),"avatar capability was not authenticated");
            AvatarStore own=new AvatarStore(root.resolve("avatars"),identity.publicKey()),remote=new AvatarStore(root.resolve("avatars"),channel.remotePublicKey());
            byte[] avatarPng=avatarPng();Files.write(root.resolve("java-avatar.png"),avatarPng);own.save(avatarPng);
            BlockingQueue<AvatarSnapshot> avatarNotices=new LinkedBlockingQueue<>();BlockingQueue<IOException> avatarFailures=new LinkedBlockingQueue<>();
            byte[] content=new byte[3*TransferLimits.BLOCK_BYTES+17];for(int i=0;i<content.length;i++)content[i]=(byte)(i*31+7);Path source=root.resolve("java-source.bin");Files.write(source,content);
            BlockingQueue<AttachmentRecord> notices=new LinkedBlockingQueue<>();
            try(AvatarSync avatars=new AvatarSync(own,remote,p->{try{channel.write(ProtocolV4.encodeAvatar(p));return true;}catch(IOException e){return false;}},new AvatarSync.Listener(){
                public void onChanged(AvatarSnapshot saved){avatarNotices.add(saved);}
                public void onProtocolError(IOException e){avatarFailures.add(e);}
                public void onFailure(AvatarPacket.Code code){avatarFailures.add(new IOException("Avatar failure "+code));}
            });AttachmentTransferV2 files=new AttachmentTransferV2(root.resolve("received"),identity.fingerprint(),DeviceIdentity.fingerprint(channel.remotePublicKey()),channel.connectionGeneration(),false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket p){try{channel.write(ProtocolV4.encodeTransfer(p));return true;}catch(IOException e){return false;}}
                public void abort(){try{channel.close();}catch(Exception ignored){}}
            },notices::add)) {
                avatars.start();
                files.offer(new FileAttachmentSource(source),"来自Java.bin","application/octet-stream").get(5,TimeUnit.SECONDS);
                String textId=UUID.randomUUID().toString().toUpperCase(Locale.ROOT),whiteId=UUID.randomUUID().toString();
                channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,textId,"Java 你好 👩‍💻\n"+safety,1234)));
                channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,whiteId,"\u00a0\u2003",1235)));
                channel.write(ProtocolV4.encode(new Frame(Frame.PING,"","",0)));
                BlockingQueue<ProtocolV4.Record> packets=new ArrayBlockingQueue<>(64);
                java.util.concurrent.atomic.AtomicReference<Throwable> readFailure=new java.util.concurrent.atomic.AtomicReference<>();
                Thread reader=new Thread(()->{try{while(true)packets.put(ProtocolV4.decodeRecord(channel.read()));}catch(Throwable failure){readFailure.set(failure);}},"terminal-interop-reader");reader.setDaemon(true);reader.start();
                boolean text=false,ack=false,white=false,pong=false,received=false,delivered=false,avatarReceived=false,avatarSent=false;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                for(int records=0;records<1500&&System.nanoTime()<deadline&&!(text&&ack&&white&&pong&&received&&delivered&&avatarReceived&&avatarSent);records++) {
                    ProtocolV4.Record record=packets.poll(50,TimeUnit.MILLISECONDS);
                    if(record!=null&&record.avatar!=null){
                        if(record.avatar.kind==AvatarPacket.Kind.RESULT&&record.avatar.code==AvatarPacket.Code.OK)avatarSent=true;
                        require(avatars.receive(record.avatar),"avatar admission rejected");
                    }
                    else if(record!=null&&record.transfer!=null){files.receive(record.transfer,true);}
                    else if(record!=null) switch(record.frame.type) {
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
                    AvatarSnapshot avatar;while((avatar=avatarNotices.poll())!=null){require(Arrays.equals(avatar.png(),Files.readAllBytes(root.getParent().resolve("rust-avatar.png"))),"Rust avatar bytes differ");avatarReceived=true;}
                    if(!avatarFailures.isEmpty())throw avatarFailures.poll();
                    if(record==null&&readFailure.get()!=null)throw new IOException("Java fixture reader failed",readFailure.get());
                }
                require(text&&ack&&white&&pong&&received&&delivered&&avatarReceived&&avatarSent,"incomplete exchange text="+text+" ack="+ack+" whitespace="+white+" pong="+pong+" received="+received+" delivered="+delivered+" avatarReceived="+avatarReceived+" avatarSent="+avatarSent);
                channel.write(ProtocolV4.encode(new Frame(Frame.BYE,"","",0)));
            }
        }
    }
}
