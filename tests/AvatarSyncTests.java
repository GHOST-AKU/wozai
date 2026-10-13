package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

/** Real Noise sessions and private-file avatar commits, under a 32 MiB heap. */
public final class AvatarSyncTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Action {void run()throws Exception;}
    private static void rejects(Action action)throws Exception {try{action.run();}catch(IOException|IllegalArgumentException expected){checks++;return;}throw new AssertionError("Invalid avatar boundary was accepted");}
    private static void await(java.util.function.BooleanSupplier condition,String message)throws Exception {long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!condition.getAsBoolean()&&System.nanoTime()<limit)Thread.sleep(10);check(condition.getAsBoolean(),message);}
    private static byte[] randomKey(){byte[] key=new byte[32];new SecureRandom().nextBytes(key);return key;}
    private static String hash(byte[] bytes)throws Exception{return AttachmentInfo.hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static void pngChunk(DataOutputStream out,String type,byte[] bytes)throws Exception {byte[] name=type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);out.writeInt(bytes.length);out.write(name);out.write(bytes);CRC32 crc=new CRC32();crc.update(name);crc.update(bytes);out.writeInt((int)crc.getValue());}
    private static byte[] png(int edge,int seed)throws Exception {
        ByteArrayOutputStream file=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(file);out.write(new byte[]{(byte)137,80,78,71,13,10,26,10});ByteArrayOutputStream h=new ByteArrayOutputStream();DataOutputStream header=new DataOutputStream(h);header.writeInt(edge);header.writeInt(edge);header.write(new byte[]{8,6,0,0,0});pngChunk(out,"IHDR",h.toByteArray());
        byte[] row=new byte[edge*4+1];Random random=new Random(seed);ByteArrayOutputStream data=new ByteArrayOutputStream();try(DeflaterOutputStream zip=new DeflaterOutputStream(data)){for(int y=0;y<edge;y++){random.nextBytes(row);row[0]=0;zip.write(row);}}pngChunk(out,"IDAT",data.toByteArray());pngChunk(out,"IEND",new byte[0]);return file.toByteArray();
    }
    private static AvatarProfile profile(long revision,byte[] bytes,int edge)throws Exception{return new AvatarProfile(revision,hash(bytes),"png",edge,edge,bytes.length);}
    private static void codec()throws Exception {
        byte[] bytes=png(16,2);AvatarProfile profile=profile(1,bytes,16);String id=UUID.randomUUID().toString();
        for(AvatarPacket p:new AvatarPacket[]{AvatarPacket.profile(profile),AvatarPacket.profile(AvatarProfile.defaults(2)),AvatarPacket.request(profile,id),AvatarPacket.chunk(id,1,profile.avatarHash,0,bytes),AvatarPacket.ack(id,1,profile.avatarHash,bytes.length),AvatarPacket.result(id,1,profile.avatarHash,AvatarPacket.Code.OK),AvatarPacket.cancel(id,1,profile.avatarHash,AvatarPacket.Code.SUPERSEDED)}){
            byte[] encoded=AvatarCodec.encode(p);AvatarPacket decoded=AvatarCodec.decode(encoded);check(decoded.kind==p.kind&&decoded.revision==p.revision&&decoded.offset==p.offset&&Arrays.equals(decoded.data,p.data)&&Objects.equals(decoded.profile,p.profile),"Avatar packet round trip changed");ProtocolV4.Record record=ProtocolV4.decodeRecord(ProtocolV4.encodeAvatar(p));check(record.avatar!=null&&record.frame==null&&record.transfer==null,"Avatar record enters a chat/file route");
        }
        rejects(()->AvatarPacket.chunk(id,1,profile.avatarHash,0,new byte[4097]));rejects(()->AvatarPacket.chunk(id,1,profile.avatarHash,0,new byte[0]));rejects(()->AvatarPacket.chunk(id,1,profile.avatarHash,AvatarSpec.MAX_BYTES,new byte[]{1}));rejects(()->AvatarPacket.ack(id,-1,profile.avatarHash,0));rejects(()->AvatarPacket.request(AvatarProfile.defaults(3),id));rejects(()->AvatarPacket.cancel(id,1,profile.avatarHash,AvatarPacket.Code.OK));rejects(()->AvatarPacket.result(id,1,profile.avatarHash,AvatarPacket.Code.TIMEOUT));
        byte[] encoded=AvatarCodec.encode(AvatarPacket.profile(profile));for(int length:new int[]{0,5,encoded.length-1})rejects(()->AvatarCodec.decode(Arrays.copyOf(encoded,length)));
        byte[] trailing=Arrays.copyOf(encoded,encoded.length+1);rejects(()->AvatarCodec.decode(trailing));byte[] kind=encoded.clone();kind[9]=(byte)100;rejects(()->AvatarCodec.decode(kind));byte[] utf8=encoded.clone();utf8[20]=(byte)0xff;rejects(()->AvatarCodec.decode(utf8));byte[] flag=encoded.clone();flag[91]=2;rejects(()->AvatarCodec.decode(flag));
        byte[] packet=AvatarCodec.encode(AvatarPacket.profile(profile));rejects(()->ProtocolV4.encode(new Frame(Frame.AVATAR,"bad","",0,0,packet)));rejects(()->ProtocolV4.encode(new Frame(Frame.AVATAR,"","bad",0,0,packet)));rejects(()->ProtocolV4.encode(new Frame(Frame.AVATAR,"","",1,0,packet)));
    }
    private static Socket[] sockets()throws Exception {try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){Socket a=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()),b=server.accept();a.setTcpNoDelay(true);b.setTcpNoDelay(true);return new Socket[]{a,b};}}
    private static final class End implements FramedSession.Listener {
        final CountDownLatch hello=new CountDownLatch(1),ready=new CountDownLatch(1),closed=new CountDownLatch(1);final BlockingQueue<AvatarPacket> avatars=new LinkedBlockingQueue<>();final BlockingQueue<String> order=new LinkedBlockingQueue<>();final BlockingQueue<Frame> text=new LinkedBlockingQueue<>();final BlockingQueue<String> receipts=new LinkedBlockingQueue<>();final BlockingQueue<TransferPacket> files=new LinkedBlockingQueue<>();FramedSession wire;Sync engine;
        public void onHello(Frame frame){hello.countDown();}public void onReady(){ready.countDown();}public void onClosed(UiText reason){closed.countDown();if(engine!=null)engine.close();}public void onText(Frame frame){order.add("TEXT");text.add(frame);wire.acknowledge(frame.id);}public void onAck(String id){receipts.add(id);}public void onAvatar(AvatarPacket packet){order.add("AVATAR_"+packet.kind);if(engine!=null){try{if(!engine.receive(packet))wire.close(UiText.EMPTY);}catch(Exception failure){throw new IllegalStateException(failure);}}else avatars.add(packet);}public void onTransfer(TransferPacket packet){order.add("FILE");files.add(packet);}
    }
    private static final class Pair implements AutoCloseable {
        final End a=new End(),b=new End();final DeviceIdentity aid=DeviceIdentity.generate(),bid=DeviceIdentity.generate();
        Pair(boolean ac,boolean bc)throws Exception {Socket[] sockets=sockets();a.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",aid,randomKey(),true,a,ac);b.wire=FramedSession.secure(SessionTests.connection(sockets[1]),CoreTests.B,"B",bid,randomKey(),false,b,bc);a.wire.start();b.wire.start();check(a.hello.await(5,TimeUnit.SECONDS)&&b.hello.await(5,TimeUnit.SECONDS),"Noise capability handshake failed");}
        void ready()throws Exception {a.wire.approve();b.wire.approve();check(a.ready.await(5,TimeUnit.SECONDS)&&b.ready.await(5,TimeUnit.SECONDS),"Both avatar peers did not become READY");}
        public void close(){a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);}
    }
    private static void sessions()throws Exception {
        AvatarPacket packet=AvatarPacket.profile(AvatarProfile.defaults(3));
        for(boolean ac:new boolean[]{false,true})for(boolean bc:new boolean[]{false,true})try(Pair p=new Pair(ac,bc)){
            check(!p.a.wire.avatarsSupported()&&!p.a.wire.sendAvatar(packet),"UnREADY session sent avatar metadata");p.a.wire.approve();Thread.sleep(25);check(!p.a.wire.avatarsSupported(),"One READY enabled avatar capability");p.b.wire.approve();check(p.a.ready.await(5,TimeUnit.SECONDS)&&p.b.ready.await(5,TimeUnit.SECONDS),"Avatar capability approval failed");check(p.a.wire.avatarsSupported()==(ac&&bc)&&p.b.wire.avatarsSupported()==(ac&&bc),"Capabilities 3/7 negotiated incorrectly");
            check(p.a.wire.sendAvatar(packet)==(ac&&bc),"Unsupported peer received an avatar record");if(ac&&bc)check(p.b.avatars.poll(3,TimeUnit.SECONDS).profile.revision==3,"Encrypted avatar packet changed");else check(p.b.avatars.isEmpty(),"Non-avatar listener was invoked");
            String id=UUID.randomUUID().toString();check(p.a.wire.send(new Frame(Frame.TEXT,id,"chat stays independent",1)),"Chat rejected during avatar negotiation");check(p.b.text.poll(3,TimeUnit.SECONDS)!=null&&id.equals(p.a.receipts.poll(3,TimeUnit.SECONDS)),"Avatar frame changed ordinary chat receipt");TransferPacket file=TransferPacket.data(UUID.randomUUID().toString(),UUID.randomUUID().toString(),p.a.wire.connectionGeneration(),2,0,new byte[]{1,2});check(p.a.wire.sendTransfer(file)&&p.b.files.poll(3,TimeUnit.SECONDS)!=null,"Optional avatars changed ordinary files");
        }
        for(boolean enabled:new boolean[]{false,true}){
            Socket[] sockets=sockets();End end=new End();end.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,end,true);
            try(NoiseRecordChannel raw=new NoiseRecordChannel(SessionTests.connection(sockets[1]),false,DeviceIdentity.generate(),CoreTests.B,"B",randomKey(),null,null,enabled)){end.wire.start();raw.establish();check(end.hello.await(3,TimeUnit.SECONDS),"Raw avatar handshake failed");if(!enabled){end.wire.approve();raw.write(ProtocolV4.encode(new Frame(Frame.READY,"","",1)));check(end.ready.await(3,TimeUnit.SECONDS),"Raw legacy capability session not READY");}raw.write(ProtocolV4.encodeAvatar(packet));check(end.closed.await(3,TimeUnit.SECONDS)&&end.avatars.isEmpty(),"Unnegotiated/unREADY avatar reached listener");}finally{end.wire.close(UiText.EMPTY);}
        }
        try(SessionTests.Pair p=new SessionTests.Pair()){p.approve();check(!p.a.avatarsSupported()&&!p.a.sendAvatar(packet),"Plaintext NIM3 advertises avatars");}
    }
    private static final class Sync implements AutoCloseable {
        final Object value;final Class<?> type;final BlockingQueue<AvatarPacket> sent=new LinkedBlockingQueue<>();final BlockingQueue<AvatarSnapshot> changed=new LinkedBlockingQueue<>();final BlockingQueue<IOException> errors=new LinkedBlockingQueue<>();
        Sync(AvatarStore local,AvatarStore remote)throws Exception {this(local,remote,null);}
        Sync(AvatarStore local,AvatarStore remote,FramedSession wire)throws Exception {
            try{type=Class.forName("dev.ghost.nearbyim.core.AvatarSync");}catch(ClassNotFoundException missing){throw new AssertionError("Dedicated bounded avatar state machine is missing",missing);}
            Class<?> transport=Class.forName(type.getName()+"$Transport"),listener=Class.forName(type.getName()+"$Listener");Object send=java.lang.reflect.Proxy.newProxyInstance(transport.getClassLoader(),new Class<?>[]{transport},(p,m,args)->{if(m.getName().equals("send")){sent.add((AvatarPacket)args[0]);return wire==null||wire.sendAvatar((AvatarPacket)args[0]);}return null;});Object events=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(p,m,args)->{if(m.getName().equals("onChanged"))changed.add((AvatarSnapshot)args[0]);if(m.getName().equals("onProtocolError")){errors.add((IOException)args[0]);if(wire!=null)wire.close(UiText.EMPTY);}return null;});try{value=type.getConstructor(AvatarStore.class,AvatarStore.class,transport,listener).newInstance(local,remote,send,events);}catch(InvocationTargetException failure){if(failure.getCause() instanceof Exception e)throw e;throw failure;}
        }
        void start()throws Exception {type.getMethod("start").invoke(value);}void localChanged()throws Exception {type.getMethod("localChanged").invoke(value);}boolean receive(AvatarPacket p)throws Exception{return (boolean)type.getMethod("receive",AvatarPacket.class).invoke(value,p);}public void close(){try{type.getMethod("close").invoke(value);if(!Thread.currentThread().getName().equals("nearby-avatar")){Field field=type.getDeclaredField("worker");field.setAccessible(true);check(((ExecutorService)field.get(value)).awaitTermination(5,TimeUnit.SECONDS),"Avatar fixture worker did not terminate");Field receiving=type.getDeclaredField("incoming");receiving.setAccessible(true);await(()->{try{return receiving.get(value)==null;}catch(IllegalAccessException failure){return false;}},"Avatar fixture file cleanup did not finish");}}catch(Exception e){throw new AssertionError(e);}}
        AvatarPacket take(AvatarPacket.Kind kind)throws Exception {long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);while(System.nanoTime()<limit){AvatarPacket p=sent.poll(50,TimeUnit.MILLISECONDS);if(p!=null&&p.kind==kind)return p;}throw new AssertionError("Missing avatar packet "+kind);}
    }
    private static void clean(Path root)throws Exception {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private static void sync()throws Exception {
        Path root=Files.createTempDirectory("avatar-sync-test-");try{
            AvatarStore own=new AvatarStore(root.resolve("receiver"),SafetyCodeTests.A),remote=new AvatarStore(root.resolve("receiver"),SafetyCodeTests.B);byte[] first=png(96,12),second=png(32,7);AvatarSnapshot local=own.save(second);AvatarProfile p=profile(5,first,96);
            try(Sync engine=new Sync(own,remote)){
                check(!engine.receive(AvatarPacket.profile(p)),"Avatar packet admitted before engine start");engine.start();check(engine.take(AvatarPacket.Kind.PROFILE_UPDATE).profile.equals(local.profile()),"Start advertises wrong local profile");check(engine.receive(AvatarPacket.profile(p)),"Profile update was not admitted");AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);check(request.revision==5&&request.avatarHash.equals(p.avatarHash),"Request differs from received metadata");
                for(int offset=0;offset<first.length;offset+=4096){int end=Math.min(first.length,offset+4096);check(engine.receive(AvatarPacket.chunk(request.transferId,5,p.avatarHash,offset,Arrays.copyOfRange(first,offset,end))),"Chunk admission failed");AvatarPacket ack=engine.take(AvatarPacket.Kind.ACK);check(ack.offset==end,"ACK preceded avatar write or lost cumulative offset");}
                check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.OK,"Validated image was not committed");check(Arrays.equals(remote.snapshot().png(),first)&&remote.snapshot().profile().revision==5,"Remote image differs from final PNG bytes");check(Arrays.equals(own.snapshot().png(),second)&&own.snapshot().profile().equals(local.profile()),"Received avatar overwrote local profile");check(engine.changed.poll(3,TimeUnit.SECONDS)!=null,"Contact UI update missing");
                check(engine.receive(AvatarPacket.profile(AvatarProfile.defaults(6))),"Default update rejected");await(()->{try{return remote.snapshot().profile().revision==6;}catch(IOException e){return false;}},"Default tombstone not saved");check(remote.snapshot().png().length==0,"Default retains stale image");engine.receive(AvatarPacket.profile(p));Thread.sleep(80);check(remote.snapshot().profile().revision==6,"Old image metadata resurrected default");
            }
            // Reconnect with a verified hash already referenced by the local owner.
            byte[] cache=second;AvatarProfile cached=profile(7,cache,32);try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(cached));await(()->{try{return remote.snapshot().profile().revision==7;}catch(IOException e){return false;}},"Verified cache hit was not adopted");Thread.sleep(80);check(engine.sent.stream().noneMatch(x->x.kind==AvatarPacket.Kind.REQUEST),"Verified cache hit downloaded the image again");check(Arrays.equals(remote.snapshot().png(),cache),"Cache reuse changed PNG bytes");}
            check(new AvatarStore(root.resolve("receiver"),SafetyCodeTests.B).snapshot().profile().revision==7,"Remote reference did not survive restart");
        }finally{clean(root);}
    }
    private static long parts(Path root)throws IOException {try(var files=Files.list(root)){return files.filter(p->p.getFileName().toString().startsWith(".avatar-sync-")).count();}}
    private static void deliver(Sync engine,AvatarPacket request,AvatarProfile profile,byte[] bytes)throws Exception {
        for(int offset=0;offset<bytes.length;offset+=4096){int end=Math.min(bytes.length,offset+4096);check(engine.receive(AvatarPacket.chunk(request.transferId,profile.revision,profile.avatarHash,offset,Arrays.copyOfRange(bytes,offset,end))),"Avatar bytes exceeded bounded admission");engine.take(AvatarPacket.Kind.ACK);}
    }
    private static void faults()throws Exception {
        Path root=Files.createTempDirectory("avatar-faults-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] previous=png(16,10),image=png(96,13);remote.save(previous);own.save(previous);AvatarProfile good=profile(5,image,96);
            try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(good));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);check(parts(root)==1,"Incoming transfer has no distinct private partial file");
                engine.receive(AvatarPacket.chunk(request.transferId,good.revision,good.avatarHash,4096,Arrays.copyOfRange(image,4096,8192)));check(engine.errors.poll(3,TimeUnit.SECONDS)!=null,"Out-of-order chunk did not close avatar protocol");check(Arrays.equals(remote.snapshot().png(),previous),"Protocol error changed previous avatar");await(()->{try{return parts(root)==0;}catch(IOException e){return false;}},"Protocol error leaked partial avatar");
            }
            byte[] invalid=image.clone();invalid[29]^=1;AvatarProfile invalidProfile=profile(6,invalid,96);
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(invalidProfile));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);deliver(engine,request,invalidProfile,invalid);check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.INVALID_IMAGE,"Real invalid PNG passed commit");check(engine.errors.isEmpty()&&Arrays.equals(remote.snapshot().png(),previous),"PNG error closed protocol or erased old avatar");}
            ByteArrayOutputStream broken=new ByteArrayOutputStream();DataOutputStream malformed=new DataOutputStream(broken);malformed.write(png(1,19),0,33);pngChunk(malformed,"IDAT",new byte[]{1,2,3,4});pngChunk(malformed,"IEND",new byte[0]);byte[] badZlib=broken.toByteArray();AvatarProfile badPixels=profile(7,badZlib,1);
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(badPixels));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);deliver(engine,request,badPixels,badZlib);check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.INVALID_IMAGE,"Valid-CRC corrupt zlib pixels passed PNG validation");check(engine.errors.isEmpty()&&Arrays.equals(remote.snapshot().png(),previous),"Zlib image failure changed protocol or previous avatar");}
            byte[] wrong=image.clone();wrong[100]^=1;
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(good));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);deliver(engine,request,good,wrong);check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.HASH_MISMATCH,"Wrong hash passed avatar validation");check(engine.errors.isEmpty()&&Arrays.equals(remote.snapshot().png(),previous),"Hash task error replaced old image");}
            AvatarProfile lied=profile(7,image,32);
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(lied));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);deliver(engine,request,lied,image);check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.INVALID_IMAGE,"Advertised dimensions bypassed PNG decode");}
            try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(good));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);engine.receive(AvatarPacket.chunk(request.transferId,good.revision,good.avatarHash,0,Arrays.copyOf(image,4096)));engine.take(AvatarPacket.Kind.ACK);
                Field incoming=engine.type.getDeclaredField("incoming");incoming.setAccessible(true);Object task=incoming.get(engine.value);Field progress=task.getClass().getSuperclass().getDeclaredField("progress");progress.setAccessible(true);progress.setLong(task,System.nanoTime()-TimeUnit.SECONDS.toNanos(31));check(engine.take(AvatarPacket.Kind.CANCEL).code==AvatarPacket.Code.TIMEOUT,"Thirty-second avatar inactivity did not cancel");check(parts(root)==0&&Arrays.equals(remote.snapshot().png(),previous),"Timeout leaked partial or changed previous image");
            }
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(good));engine.take(AvatarPacket.Kind.REQUEST);engine.close();check(parts(root)==0,"Close leaked private avatar partial");check(!engine.receive(AvatarPacket.profile(AvatarProfile.defaults(8))),"Closed engine admits new profile");}
        }finally{clean(root);}
    }
    private static void windowAndSupersession()throws Exception {
        Path root=Files.createTempDirectory("avatar-window-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] image=png(96,11),replacement=png(32,12);AvatarSnapshot local=own.save(image);
            try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);String id=UUID.randomUUID().toString();engine.receive(AvatarPacket.request(local.profile(),id));AvatarPacket first=null;long end=0;for(int i=0;i<4;i++){AvatarPacket chunk=engine.take(AvatarPacket.Kind.CHUNK);if(first==null)first=chunk;check(chunk.offset==end&&chunk.data.length<=4096,"Outgoing avatar chunk bounds/order changed");end+=chunk.data.length;}
                check(engine.sent.poll(100,TimeUnit.MILLISECONDS)==null,"Avatar sender exceeded four unacknowledged chunks");engine.receive(AvatarPacket.ack(id,local.profile().revision,local.profile().avatarHash,first.data.length));AvatarPacket fifth=engine.take(AvatarPacket.Kind.CHUNK);check(fifth.offset==end,"One ACK did not release exactly one window slot");check(engine.sent.poll(100,TimeUnit.MILLISECONDS)==null,"ACK released more than one window slot");
                engine.receive(AvatarPacket.request(local.profile(),UUID.randomUUID().toString()));check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.BUSY,"Second outgoing avatar task overwrote first");own.save(replacement);engine.localChanged();check(engine.take(AvatarPacket.Kind.CANCEL).code==AvatarPacket.Code.SUPERSEDED,"Local change did not cancel old source");check(engine.take(AvatarPacket.Kind.PROFILE_UPDATE).profile.revision==2,"Local revision did not advance metadata");
            }
            AvatarProfile first=profile(8,image,96);try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(first));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);engine.receive(AvatarPacket.chunk(request.transferId,8,first.avatarHash,0,Arrays.copyOf(image,4096)));engine.take(AvatarPacket.Kind.ACK);engine.receive(AvatarPacket.profile(AvatarProfile.defaults(9)));check(engine.take(AvatarPacket.Kind.CANCEL).code==AvatarPacket.Code.SUPERSEDED,"Default did not cancel incomplete prior revision");await(()->{try{return remote.snapshot().profile().revision==9;}catch(IOException e){return false;}},"Default supersession did not persist");
                for(int i=1;i<=4;i++){engine.receive(AvatarPacket.chunk(request.transferId,8,first.avatarHash,i*4096,Arrays.copyOfRange(image,i*4096,(i+1)*4096)));Thread.sleep(20);}check(engine.errors.isEmpty()&&remote.snapshot().png().length==0,"Allowed canceled window resurrected old image");engine.receive(AvatarPacket.chunk(request.transferId,8,first.avatarHash,5*4096,Arrays.copyOfRange(image,5*4096,6*4096)));check(engine.errors.poll(3,TimeUnit.SECONDS)!=null,"Fifth late canceled chunk exceeded four-chunk window");check(remote.snapshot().profile().revision==9,"Late old task replaced default tombstone");
            }
        }finally{clean(root);}
    }
    private static void cumulativeWindow()throws Exception {
        Path root=Files.createTempDirectory("avatar-cumulative-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);AvatarSnapshot local=own.save(png(96,23));
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);String id=UUID.randomUUID().toString();engine.receive(AvatarPacket.request(local.profile(),id));long fourth=0;for(int i=0;i<4;i++){AvatarPacket p=engine.take(AvatarPacket.Kind.CHUNK);fourth=p.offset+p.data.length;}engine.receive(AvatarPacket.ack(id,local.profile().revision,local.profile().avatarHash,fourth));AvatarPacket next=engine.take(AvatarPacket.Kind.CHUNK);check(next.offset==fourth&&engine.errors.isEmpty(),"Cumulative ACK did not release the written window");for(int i=0;i<3;i++)engine.take(AvatarPacket.Kind.CHUNK);check(engine.sent.poll(100,TimeUnit.MILLISECONDS)==null,"Cumulative ACK exceeded four new chunks");}
        }finally{clean(root);}
    }
    private static void encryptedSync()throws Exception {
        Path root=Files.createTempDirectory("avatar-encrypted-sync-");try(Pair pair=new Pair(true,true)){
            AvatarStore alocal=new AvatarStore(root.resolve("a"),pair.aid.publicKey()),aremote=new AvatarStore(root.resolve("a"),pair.a.wire.remotePublicKey()),blocal=new AvatarStore(root.resolve("b"),pair.bid.publicKey()),bremote=new AvatarStore(root.resolve("b"),pair.b.wire.remotePublicKey());
            byte[] a=png(96,31),b=png(32,33);AvatarSnapshot as=alocal.save(a),bs=blocal.save(b);AvatarStore ar=aremote,br=bremote;
            try(Sync ae=new Sync(alocal,ar,pair.a.wire);Sync be=new Sync(blocal,br,pair.b.wire)){
                pair.a.engine=ae;pair.b.engine=be;pair.ready();ae.start();be.start();await(()->{try{return ar.snapshot().profile().avatarHash.equals(bs.profile().avatarHash)&&br.snapshot().profile().avatarHash.equals(as.profile().avatarHash);}catch(IOException e){return false;}},"Bidirectional encrypted avatar sync did not converge");
                check(Arrays.equals(ar.snapshot().png(),b)&&Arrays.equals(br.snapshot().png(),a),"Encrypted bidirectional images differ from sender snapshots");check(Arrays.equals(alocal.snapshot().png(),a)&&Arrays.equals(blocal.snapshot().png(),b),"Bidirectional contact sync replaced an independent local profile");check(ae.errors.isEmpty()&&be.errors.isEmpty(),"Encrypted avatar exchange raised a protocol error");String id=UUID.randomUUID().toString();check(pair.a.wire.send(new Frame(Frame.TEXT,id,"chat after avatar commits",1)),"Avatar task blocked chat");check(id.equals(pair.a.receipts.poll(3,TimeUnit.SECONDS)),"Avatar commits lost ordinary saved-text receipt");
                long requests=be.sent.stream().filter(packet->packet.kind==AvatarPacket.Kind.REQUEST).count();alocal.save(a);ae.localChanged();await(()->{try{return br.snapshot().profile().revision==2;}catch(IOException e){return false;}},"Same-hash revision did not update contact reference");check(be.sent.stream().filter(packet->packet.kind==AvatarPacket.Kind.REQUEST).count()==requests,"Same-hash revision downloaded PNG again");alocal.reset();ae.localChanged();await(()->{try{return br.snapshot().profile().revision==3&&br.snapshot().profile().avatarHash.isEmpty();}catch(IOException e){return false;}},"Encrypted default update did not persist contact tombstone");check(Arrays.equals(blocal.snapshot().png(),b),"Remote default erased this device's own avatar");
            }
        }finally{clean(root);}
    }
    private static void storageFailure()throws Exception {
        Path root=Files.createTempDirectory("avatar-storage-error-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] previous=png(16,34),image=png(32,35);remote.save(previous);own.save(previous);AvatarProfile profile=profile(5,image,32);
            try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(profile));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);Path file=root.resolve("profiles").resolve(DeviceIdentity.fingerprint(SafetyCodeTests.B)+".bin"),backup=file.resolveSibling("backup.bin");Files.move(file,backup);Files.createDirectory(file);
                try{deliver(engine,request,profile,image);check(engine.take(AvatarPacket.Kind.RESULT).code==AvatarPacket.Code.STORAGE_ERROR,"Failed metadata commit reported avatar success");check(engine.errors.isEmpty(),"Storage task failure closed authenticated protocol");}finally{Files.delete(file);Files.move(backup,file);}
                check(Arrays.equals(remote.snapshot().png(),previous)&&remote.snapshot().profile().revision==1,"Storage failure replaced prior contact avatar");check(parts(root)==0,"Storage failure leaked avatar partial");
            }
        }finally{clean(root);}
    }
    private static void namespaceAndOrphans()throws Exception {
        Path root=Files.createTempDirectory("avatar-namespaces-");try{
            AvatarStore local=new AvatarStore(root,SafetyCodeTests.A),same=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);
            rejects(()->new Sync(local,same));rejects(()->new Sync(local,new AvatarStore(root.resolve("other"),SafetyCodeTests.A)));
            Path orphan=root.resolve(".avatar-sync-orphan.part");Files.write(orphan,new byte[4096]);byte[] image=png(32,44);AvatarProfile profile=profile(5,image,32);
            try(Sync engine=new Sync(local,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);check(!Files.exists(orphan),"Reconnect did not reclaim an orphan avatar partial");engine.receive(AvatarPacket.profile(profile));engine.take(AvatarPacket.Kind.REQUEST);check(parts(root)==1,"Active partial missing");
                try(Sync other=new Sync(local,new AvatarStore(root,SafetyCodeTests.B))){other.start();other.take(AvatarPacket.Kind.PROFILE_UPDATE);check(parts(root)==1,"Another identity store reclaimed active sync partial");}
            }
            check(parts(root)==0,"Namespace cleanup leaked active partial");
        }finally{clean(root);}
    }
    private static void boundedAdmission()throws Exception {
        Path root=Files.createTempDirectory("avatar-admission-");CountDownLatch release=new CountDownLatch(1);try{
            AvatarStore local=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);try(Sync engine=new Sync(local,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);Field field=engine.type.getDeclaredField("worker");field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(engine.value);CountDownLatch busy=new CountDownLatch(1);worker.execute(()->{busy.countDown();try{release.await();}catch(InterruptedException ignored){}});check(busy.await(3,TimeUnit.SECONDS),"Admission fixture worker did not block");
                AvatarProfile profile=profile(5,png(32,41),32);for(int i=0;i<8;i++)check(engine.receive(AvatarPacket.profile(profile)),"Bounded control budget rejected permitted control");check(!engine.receive(AvatarPacket.profile(profile)),"Avatar control queue admitted ninth waiting control");String id=UUID.randomUUID().toString();for(int i=0;i<4;i++)check(engine.receive(AvatarPacket.chunk(id,5,profile.avatarHash,i*4096,new byte[4096])),"Bounded receive window rejected permitted chunk");check(!engine.receive(AvatarPacket.chunk(id,5,profile.avatarHash,4*4096,new byte[4096])),"Avatar byte queue exceeded 16 KiB window");
            }
        }finally{release.countDown();clean(root);}
    }
    private static void unknownCapabilities()throws Exception {
        for(int capabilities:new int[]{0,2,11,15}){
            Socket[] sockets=sockets();End end=new End();end.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,end,true);
            try(NoiseRecordChannel raw=new NoiseRecordChannel(SessionTests.connection(sockets[1]),false,DeviceIdentity.generate(),CoreTests.B,"B",randomKey(),null,null,true)){
                Field field=NoiseRecordChannel.class.getDeclaredField("localClaim");field.setAccessible(true);byte[] claim=(byte[])field.get(raw);ByteBuffer.wrap(claim).putInt(claim.length-16,capabilities);end.wire.start();rejects(raw::establish);check(end.closed.await(3,TimeUnit.SECONDS)&&end.hello.getCount()==1&&!end.wire.avatarsSupported(),"Unknown or incomplete capability claim reached consent");
            }finally{end.wire.close(UiText.EMPTY);}
        }
    }
    private static void encryptedTamper()throws Exception {
        Socket[] sockets=sockets();End end=new End();AtomicBoolean corrupt=new AtomicBoolean();StreamConnection base=SessionTests.connection(sockets[1]);StreamConnection altered=new StreamConnection(){
            public InputStream input()throws IOException{return base.input();}public String label(){return "encrypted avatar tamper fixture";}public void close()throws IOException{base.close();}
            public OutputStream output()throws IOException {OutputStream out=base.output();return new OutputStream(){public void write(int value)throws IOException{out.write(value);}public void flush()throws IOException{out.flush();}public void write(byte[] bytes,int offset,int count)throws IOException{if(corrupt.compareAndSet(true,false)){byte[] changed=Arrays.copyOfRange(bytes,offset,offset+count);changed[changed.length-1]^=1;out.write(changed);}else out.write(bytes,offset,count);}};}
        };
        end.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,end,true);
        try(NoiseRecordChannel raw=new NoiseRecordChannel(altered,false,DeviceIdentity.generate(),CoreTests.B,"B",randomKey(),null,null,true)){
            end.wire.start();raw.establish();check(end.hello.await(3,TimeUnit.SECONDS),"Tamper fixture not authenticated");end.wire.approve();raw.write(ProtocolV4.encode(new Frame(Frame.READY,"","",1)));check(end.ready.await(3,TimeUnit.SECONDS),"Tamper fixture not READY");corrupt.set(true);raw.write(ProtocolV4.encodeAvatar(AvatarPacket.profile(AvatarProfile.defaults(8))));check(end.closed.await(3,TimeUnit.SECONDS)&&end.avatars.isEmpty()&&!end.wire.endToEndEncrypted(),"Tampered encrypted avatar record reached listener or fell back");
        }finally{end.wire.close(UiText.EMPTY);}
    }
    private static void canceledRejectedRequest()throws Exception {
        Path root=Files.createTempDirectory("avatar-rejected-race-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);AvatarSnapshot old=own.save(png(32,81));own.reset();
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);String id=UUID.randomUUID().toString();engine.receive(AvatarPacket.request(old.profile(),id));check(engine.take(AvatarPacket.Kind.CANCEL).code==AvatarPacket.Code.SUPERSEDED,"Stale request not superseded");engine.receive(AvatarPacket.cancel(id,old.profile().revision,old.profile().avatarHash,AvatarPacket.Code.SUPERSEDED));Thread.sleep(80);check(engine.errors.isEmpty(),"Valid late cancellation of rejected request closed session");}
        }finally{clean(root);}
    }
    private static void bindingTamper()throws Exception {
        Socket[] sockets=sockets();End end=new End();AtomicReference<NoiseRecordChannel> reference=new AtomicReference<>();StreamConnection base=SessionTests.connection(sockets[0]);StreamConnection changed=new StreamConnection(){
            public InputStream input()throws IOException{return base.input();}public void close()throws IOException{base.close();}public String label(){return "capability root proof binding fixture";}
            public OutputStream output()throws IOException {OutputStream out=base.output();return new OutputStream(){int handshakes;boolean changeClaim;public void write(int value)throws IOException{out.write(value);}public void flush()throws IOException{out.flush();}public void write(byte[] bytes,int offset,int count)throws IOException{
                out.write(bytes,offset,count);if(count==10&&ByteBuffer.wrap(bytes,offset+4,4).getInt()==0x4e494d34&&(bytes[offset+9]&255)==1){changeClaim=++handshakes==2;return;}
                if(changeClaim){changeClaim=false;try{Field field=NoiseRecordChannel.class.getDeclaredField("localClaim");field.setAccessible(true);byte[] claim=(byte[])field.get(reference.get());ByteBuffer.wrap(claim).putInt(claim.length-16,3);}catch(ReflectiveOperationException failure){throw new IOException(failure);}}
            }};}
        };
        end.wire=FramedSession.secure(SessionTests.connection(sockets[1]),CoreTests.B,"B",DeviceIdentity.generate(),randomKey(),false,end,true);
        try(NoiseRecordChannel raw=new NoiseRecordChannel(changed,true,DeviceIdentity.generate(),CoreTests.A,"A",randomKey(),null,null,true)){
            reference.set(raw);end.wire.start();rejects(raw::establish);check(end.closed.await(3,TimeUnit.SECONDS)&&end.hello.getCount()==1&&!raw.verified(),"Changed capability claim was not bound to both root proofs");
        }finally{end.wire.close(UiText.EMPTY);}
    }
    private static void queuedCancellationAndChat()throws Exception {
        Socket[] sockets=sockets();End a=new End(),b=new End();CountDownLatch fileStarted=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean hold=new AtomicBoolean();StreamConnection base=SessionTests.connection(sockets[0]);StreamConnection slow=new StreamConnection(){
            public InputStream input()throws IOException{return base.input();}public void close()throws IOException{base.close();}public String label(){return "busy ordinary file fixture";}
            public OutputStream output()throws IOException{OutputStream out=base.output();return new OutputStream(){public void write(int value)throws IOException{out.write(value);}public void flush()throws IOException{out.flush();}public void write(byte[] bytes,int offset,int count)throws IOException{if(count>20*1024&&hold.compareAndSet(true,false)){fileStarted.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("Fixture release timed out");}catch(InterruptedException interrupted){throw new InterruptedIOException();}}out.write(bytes,offset,count);}};}
        };
        a.wire=FramedSession.secure(slow,CoreTests.A,"A",DeviceIdentity.generate(),randomKey(),true,a,true);b.wire=FramedSession.secure(SessionTests.connection(sockets[1]),CoreTests.B,"B",DeviceIdentity.generate(),randomKey(),false,b,true);
        try{
            a.wire.start();b.wire.start();check(a.hello.await(3,TimeUnit.SECONDS)&&b.hello.await(3,TimeUnit.SECONDS),"Busy stream failed greeting");a.wire.approve();b.wire.approve();check(a.ready.await(3,TimeUnit.SECONDS)&&b.ready.await(3,TimeUnit.SECONDS),"Busy stream failed READY");hold.set(true);
            String fileId=UUID.randomUUID().toString(),source=UUID.randomUUID().toString();byte[] data=new byte[TransferLimits.DATA_BYTES];new Random(17).nextBytes(data);TransferPacket first=TransferPacket.data(fileId,source,a.wire.connectionGeneration(),2L*data.length,0,data),second=TransferPacket.data(fileId,source,a.wire.connectionGeneration(),2L*data.length,data.length,data);check(a.wire.sendTransfer(first)&&fileStarted.await(3,TimeUnit.SECONDS),"Ordinary file did not occupy active record");check(a.wire.sendTransfer(second),"Second ordinary file record rejected");String canceled=UUID.randomUUID().toString(),hash="0".repeat(64);
            for(int i=0;i<4;i++)check(a.wire.sendAvatar(AvatarPacket.chunk(canceled,1,hash,i*4096,new byte[4096])),"Queued permitted avatar window rejected");check(!a.wire.sendAvatar(AvatarPacket.chunk(canceled,1,hash,4*4096,new byte[4096])),"Session retained a fifth queued avatar chunk");check(a.wire.sendAvatar(AvatarPacket.cancel(canceled,1,hash,AvatarPacket.Code.CANCELED)),"Avatar cancellation was not admitted as control");String current=UUID.randomUUID().toString();for(int i=0;i<4;i++)check(a.wire.sendAvatar(AvatarPacket.chunk(current,2,hash,i*4096,new byte[4096])),"Canceled queued bytes did not free new avatar window");String textId=UUID.randomUUID().toString();check(a.wire.send(new Frame(Frame.TEXT,textId,"text during file and avatar queue",1)),"Busy avatar queue rejected chat");long released=System.nanoTime();release.countDown();
            check("FILE".equals(b.order.poll(3,TimeUnit.SECONDS)),"Active ordinary record was interrupted");check("AVATAR_CANCEL".equals(b.order.poll(3,TimeUnit.SECONDS))&&"TEXT".equals(b.order.poll(3,TimeUnit.SECONDS)),"Text/cancel did not precede queued ordinary/avatar data");check(textId.equals(a.receipts.poll(3,TimeUnit.SECONDS)),"Busy avatar queue lost saved-text receipt");long latency=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-released);check(latency<1500,"Busy avatar queue delayed text beyond 1.5 seconds");
            TransferPacket received=b.files.poll(3,TimeUnit.SECONDS),next=b.files.poll(3,TimeUnit.SECONDS);check(received!=null&&next!=null&&Arrays.equals(received.data,data)&&Arrays.equals(next.data,data)&&next.offset==data.length,"Avatar cancellation changed ordinary file contents");AvatarPacket cancel=b.avatars.poll(3,TimeUnit.SECONDS);check(cancel!=null&&cancel.kind==AvatarPacket.Kind.CANCEL,"Peer did not observe cancellation");for(int i=0;i<4;i++){AvatarPacket chunk=b.avatars.poll(3,TimeUnit.SECONDS);check(chunk!=null&&chunk.transferId.equals(current)&&chunk.offset==i*4096,"Canceled avatar bytes leaked into new window");}check(b.avatars.poll(100,TimeUnit.MILLISECONDS)==null,"Canceled avatar queue still wrote old data");System.out.println("Avatar busy-file saved-text latency: "+latency+" ms");
        }finally{release.countDown();a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);}
    }
    private static void concurrentRevisionConflict()throws Exception {
        Path root=Files.createTempDirectory("avatar-conflict-");try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] a=png(16,70),b=png(16,71);remote.save(a);
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);remote.adopt(1,profile(5,a,16),a);check(engine.receive(AvatarPacket.profile(profile(5,b,16))),"Concurrent conflicting profile was not admitted for validation");check(engine.errors.poll(3,TimeUnit.SECONDS)!=null,"Conflicting persisted revision was treated as storage task failure");check(Arrays.equals(remote.snapshot().png(),a),"Conflicting revision replaced persisted bytes");}
        }finally{clean(root);}
    }
    private static void cancellationAdmissionPreventsCommit()throws Exception {
        Path root=Files.createTempDirectory("avatar-cancel-admission-");CountDownLatch release=new CountDownLatch(1);try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] previous=png(16,101),image=png(16,102);remote.save(previous);own.save(previous);AvatarProfile profile=profile(5,image,16);
            try(Sync engine=new Sync(own,remote)){engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(profile));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);Field field=engine.type.getDeclaredField("worker");field.setAccessible(true);ExecutorService worker=(ExecutorService)field.get(engine.value);CountDownLatch busy=new CountDownLatch(1);worker.execute(()->{busy.countDown();try{release.await();}catch(InterruptedException ignored){}});check(busy.await(3,TimeUnit.SECONDS),"Cancellation fixture worker not blocked");check(engine.receive(AvatarPacket.chunk(request.transferId,5,profile.avatarHash,0,image)),"Final chunk not admitted");check(engine.receive(AvatarPacket.cancel(request.transferId,5,profile.avatarHash,AvatarPacket.Code.CANCELED)),"Cancellation not admitted");release.countDown();Thread.sleep(100);check(remote.snapshot().profile().revision==1&&Arrays.equals(remote.snapshot().png(),previous)&&engine.changed.isEmpty(),"Admitted cancellation allowed old final chunk to commit");check(engine.errors.isEmpty()&&parts(root)==0,"Admitted cancellation leaked partial or closed session");}
        }finally{release.countDown();clean(root);}
    }
    private static void heartbeatTimeout()throws Exception {
        Path root=Files.createTempDirectory("avatar-heartbeat-timeout-");Socket[] sockets=sockets();End end=new End();DeviceIdentity localRoot=DeviceIdentity.generate(),peerRoot=DeviceIdentity.generate();
        end.wire=FramedSession.secure(SessionTests.connection(sockets[0]),CoreTests.A,"A",localRoot,randomKey(),true,end,true);sockets[1].setSoTimeout(7000);
        try(NoiseRecordChannel raw=new NoiseRecordChannel(SessionTests.connection(sockets[1]),false,peerRoot,CoreTests.B,"B",randomKey(),null,null,true)){
            end.wire.start();raw.establish();check(end.hello.await(3,TimeUnit.SECONDS),"Heartbeat timeout fixture did not verify");end.wire.approve();raw.write(ProtocolV4.encode(new Frame(Frame.READY,"","",1)));check(end.ready.await(3,TimeUnit.SECONDS),"Heartbeat timeout fixture not READY");AvatarStore own=new AvatarStore(root,localRoot.publicKey()),remote=new AvatarStore(root,end.wire.remotePublicKey());byte[] previous=png(8,111);own.save(previous);AvatarProfile profile=profile(5,png(32,112),32);
            try(Sync engine=new Sync(own,remote,end.wire)){
                end.engine=engine;engine.start();raw.write(ProtocolV4.encodeAvatar(AvatarPacket.profile(profile)));long began=0;String id=null;int heartbeats=0;boolean canceled=false;long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(34);
                while(System.nanoTime()<limit){ProtocolV4.Record record=ProtocolV4.decodeRecord(raw.read());
                    if(record.frame!=null&&record.frame.type==Frame.PING){heartbeats++;raw.write(ProtocolV4.encode(new Frame(Frame.PONG,"","",1)));continue;}
                    if(record.avatar!=null&&record.avatar.kind==AvatarPacket.Kind.REQUEST){id=record.avatar.transferId;began=System.nanoTime();continue;}
                    if(record.avatar!=null&&record.avatar.kind==AvatarPacket.Kind.CANCEL){check(record.avatar.code==AvatarPacket.Code.TIMEOUT&&record.avatar.transferId.equals(id),"Heartbeats changed avatar timeout cancellation");long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);check(elapsed>=29500&&elapsed<=33500&&heartbeats>=5,"Avatar timeout was reset by heartbeat or fired outside 30 seconds");check(end.wire.isReady()&&end.closed.getCount()==1&&engine.errors.isEmpty(),"Avatar timeout ended normal encrypted chat");check(parts(root)==0&&remote.snapshot().profile().revision==0&&Arrays.equals(own.snapshot().png(),previous),"Heartbeat timeout leaked partial or changed own avatar");System.out.println("Avatar timeout with "+heartbeats+" live heartbeats: "+elapsed+" ms");canceled=true;break;}
                }
                check(canceled,"Live heartbeats prevented avatar timeout");
            }
        }finally{end.wire.close(UiText.EMPTY);clean(root);}
    }
    private static void nonblockingClose()throws Exception {
        Path root=Files.createTempDirectory("avatar-close-ui-");CountDownLatch allowGate=new CountDownLatch(1),release=new CountDownLatch(1),holding=new CountDownLatch(1),returned=new CountDownLatch(1);AtomicReference<Throwable> error=new AtomicReference<>();Thread caller=null;
        try{
            AvatarStore own=new AvatarStore(root,SafetyCodeTests.A),remote=new AvatarStore(root,SafetyCodeTests.B);byte[] previous=png(16,121),image=png(16,122);remote.save(previous);own.save(previous);AvatarProfile profile=profile(5,image,16);
            try(Sync engine=new Sync(own,remote)){
                engine.start();engine.take(AvatarPacket.Kind.PROFILE_UPDATE);engine.receive(AvatarPacket.profile(profile));AvatarPacket request=engine.take(AvatarPacket.Kind.REQUEST);Field workerField=engine.type.getDeclaredField("worker"),gateField=engine.type.getDeclaredField("gate");workerField.setAccessible(true);gateField.setAccessible(true);ExecutorService worker=(ExecutorService)workerField.get(engine.value);Object gate=gateField.get(engine.value);
                worker.execute(()->{try{allowGate.await();}catch(InterruptedException ignored){}synchronized(gate){holding.countDown();boolean done=false;while(!done)try{release.await();done=true;}catch(InterruptedException ignored){}}});check(engine.receive(AvatarPacket.chunk(request.transferId,5,profile.avatarHash,0,image)),"Close fixture final chunk not queued");allowGate.countDown();check(holding.await(3,TimeUnit.SECONDS),"Close fixture did not hold disk gate");
                caller=new Thread(()->{try{engine.type.getMethod("close").invoke(engine.value);}catch(Throwable failure){error.set(failure);}finally{returned.countDown();}},"avatar-ui-close-fixture");caller.setDaemon(true);caller.start();
                try{check(returned.await(200,TimeUnit.MILLISECONDS),"Avatar close blocked UI behind the disk gate");check(error.get()==null,"Nonblocking avatar close threw");check(parts(root)==1,"Caller thread performed partial-file cleanup");}finally{release.countDown();caller.join(3000);}
                await(()->{try{return parts(root)==0;}catch(IOException e){return false;}},"Owned worker did not eventually clean closed partial");check(remote.snapshot().profile().revision==1&&Arrays.equals(remote.snapshot().png(),previous)&&engine.changed.isEmpty(),"Closed generation allowed late queued PNG commit");check(!engine.receive(AvatarPacket.profile(AvatarProfile.defaults(6))),"Closed generation admitted metadata");
            }
        }finally{allowGate.countDown();release.countDown();if(caller!=null)caller.join(3000);clean(root);}
    }
    public static void main(String[] args)throws Exception {codec();sessions();sync();faults();windowAndSupersession();cumulativeWindow();encryptedSync();storageFailure();namespaceAndOrphans();boundedAdmission();unknownCapabilities();encryptedTamper();bindingTamper();canceledRejectedRequest();queuedCancellationAndChat();concurrentRevisionConflict();cancellationAdmissionPreventsCommit();nonblockingClose();if(!Arrays.asList(args).contains("--quick"))heartbeatTimeout();System.out.println("AvatarSyncTests: "+checks+" binary, encrypted capability, consent, file/chat, hash-cache and independent-profile checks passed");}
}
