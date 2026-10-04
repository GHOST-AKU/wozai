package dev.ghost.nearbyim.core;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import dev.ghost.nearbyim.i18n.UiText;
public final class TransferTests {
    static final class End implements FramedSession.Listener {
        final BlockingQueue<AttachmentRecord> changes=new LinkedBlockingQueue<>();
        final BlockingQueue<Frame> texts=new LinkedBlockingQueue<>();
        final CountDownLatch hello=new CountDownLatch(1),ready=new CountDownLatch(1);
        final Path root;
        FramedSession wire;AttachmentTransfer transfers;
        volatile boolean persistedBeforeReceipt, dropReceipt,corruptChunk,failSave;
        volatile AttachmentRecord latest;
        final CountDownLatch closed=new CountDownLatch(1);
        End(Path root){this.root=root;}
        public void onHello(Frame frame){hello.countDown();}
        public void onReady(){ready.countDown();}
        public void onText(Frame frame){texts.add(frame);wire.acknowledge(frame.id);}
        public void onAck(String id){}
        public void onClosed(UiText reason){closed.countDown();if(transfers!=null)transfers.close();}
        public void onAttachment(Frame frame){transfers.receive(frame);}
        void init(Socket socket,String id)throws Exception {
            wire=new FramedSession(SessionTests.connection(socket),id,"peer",DeviceIdentity.generate(),this);
            transfers=new AttachmentTransfer(root,new AttachmentTransfer.Wire(){
                public boolean send(Frame frame){if(frame.type==Frame.FILE_RECEIPT){CoreTests.check(persistedBeforeReceipt,"Receipt precedes persistence");if(dropReceipt)return false;}if(corruptChunk&&frame.type==Frame.FILE_CHUNK){byte[] data=frame.data.clone();data[0]^=1;frame=new Frame(frame.type,frame.id,frame.body,frame.timestamp,frame.offset,data);}return wire.sendAttachment(frame);}
                public void abort(){wire.close(UiText.of("test"));}
            },r->{if(r.state.equals("received")){if(failSave)throw new IOException("Injected metadata save failure");CoreTests.check(Files.exists(AttachmentTransfer.file(root,r.info)),"Completed file missing");persistedBeforeReceipt=true;}latest=r;changes.add(r);},32768,AttachmentInfo.MAX_SIZE);
        }
        AttachmentRecord waitFor(String state)throws Exception {
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<end){AttachmentRecord r=changes.poll(100,TimeUnit.MILLISECONDS);if(r!=null&&r.state.equals(state))return r;}
            throw new AssertionError("No state "+state);
        }
    }
    static final class Pair implements AutoCloseable {
        final Path base=Files.createTempDirectory("attachments-test-");
        final End a=new End(base.resolve("a")),b=new End(base.resolve("b"));
        Pair()throws Exception {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());a.init(client,CoreTests.A);b.init(server.accept(),CoreTests.B);}
            a.wire.start();b.wire.start();CoreTests.check(a.hello.await(3,TimeUnit.SECONDS)&&b.hello.await(3,TimeUnit.SECONDS),"Handshake failed");a.wire.approve();b.wire.approve();CoreTests.check(a.ready.await(3,TimeUnit.SECONDS)&&b.ready.await(3,TimeUnit.SECONDS),"Not ready");
        }
        public void close()throws Exception{a.transfers.shutdown().get(3,TimeUnit.SECONDS);b.transfers.shutdown().get(3,TimeUnit.SECONDS);a.wire.close(UiText.EMPTY);b.wire.close(UiText.EMPTY);try(var files=Files.walk(base)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    static void run(){
        CoreTests.test("Real signed TCP file waits for consent and saves before receipt",()->{
            try(Pair p=new Pair()){
                byte[] data=new byte[32768*7+9];new Random(42).nextBytes(data);
                String id=p.a.transfers.offer(()->new ByteArrayInputStream(data),"照片.jpg","image/jpeg").get(3,TimeUnit.SECONDS);
                AttachmentRecord offer=p.b.waitFor("offered");CoreTests.check(offer.info.id.equals(id),"Wrong ID");
                CoreTests.check(!Files.exists(AttachmentTransfer.file(p.b.root,offer.info)),"Data stored before consent");
                p.b.transfers.accept(id).get(3,TimeUnit.SECONDS);AttachmentRecord received=p.b.waitFor("received");p.a.waitFor("delivered");
                CoreTests.check(Arrays.equals(data,Files.readAllBytes(AttachmentTransfer.file(p.b.root,offer.info))),"File bytes changed");
                CoreTests.check(received.transferred==data.length,"Incorrect progress");
                p.a.wire.send(new Frame(Frame.TEXT,CoreTests.B,"text after attachment",1));CoreTests.check(p.b.texts.poll(3,TimeUnit.SECONDS)!=null,"Text blocked");
            }
        });
        CoreTests.test("Zero byte file completes and reverse direction works",()->{
            try(Pair p=new Pair()){
                String id=p.b.transfers.offer(()->new ByteArrayInputStream(new byte[0]),"empty.txt","text/plain").get(3,TimeUnit.SECONDS);AttachmentRecord offer=p.a.waitFor("offered");p.a.transfers.accept(id).get(3,TimeUnit.SECONDS);p.a.waitFor("received");p.b.waitFor("delivered");
                CoreTests.check(Files.size(AttachmentTransfer.file(p.a.root,offer.info))==0,"Empty file changed");
            }
        });
        CoreTests.test("Reject and cancel release transfer slots without partial files",()->{
            try(Pair p=new Pair()){
                String id=p.a.transfers.offer(()->new ByteArrayInputStream(new byte[100]),"a.txt","text/plain").get(3,TimeUnit.SECONDS);p.b.waitFor("offered");p.b.transfers.reject(id).get(3,TimeUnit.SECONDS);p.a.waitFor("rejected");
                String second=p.a.transfers.offer(()->new ByteArrayInputStream(new byte[100]),"b.txt","text/plain").get(3,TimeUnit.SECONDS);p.b.waitFor("offered");p.a.transfers.cancel(second,true).get(3,TimeUnit.SECONDS);p.b.waitFor("canceled");
                try(var files=Files.list(p.b.root)){CoreTests.check(files.findAny().isEmpty(),"Partial content retained");}
            }
        });
        CoreTests.test("Lost receipt cannot erase a durably received attachment",()->{
            try(Pair p=new Pair()){
                p.b.dropReceipt=true;
                String id=p.a.transfers.offer(()->new ByteArrayInputStream(new byte[100]),"saved.txt","text/plain").get(3,TimeUnit.SECONDS);AttachmentRecord offer=p.b.waitFor("offered");p.b.transfers.accept(id).get(3,TimeUnit.SECONDS);p.b.waitFor("received");
                p.b.closed.await(2,TimeUnit.SECONDS);
                CoreTests.check(p.b.latest.state.equals("received"),"Lost receipt changed a saved file to "+p.b.latest.state);
                CoreTests.check(Files.exists(AttachmentTransfer.file(p.b.root,offer.info)),"Lost receipt deleted saved file");
            }
        });
        CoreTests.test("Validly signed corrupted chunks fail the whole-file digest without receipt",()->{
            try(Pair p=new Pair()){
                p.a.corruptChunk=true;String id=p.a.transfers.offer(()->new ByteArrayInputStream(new byte[100]),"bad.txt","text/plain").get(3,TimeUnit.SECONDS);AttachmentRecord offer=p.b.waitFor("offered");p.b.transfers.accept(id).get(3,TimeUnit.SECONDS);p.b.waitFor("failed");p.a.waitFor("failed");
                CoreTests.check(!p.b.persistedBeforeReceipt&&!Files.exists(AttachmentTransfer.file(p.b.root,offer.info)),"Corrupt file acknowledged or retained");
            }
        });
        CoreTests.test("Failed metadata persistence deletes final content and never acknowledges",()->{
            try(Pair p=new Pair()){
                p.b.failSave=true;String id=p.a.transfers.offer(()->new ByteArrayInputStream(new byte[100]),"bad.txt","text/plain").get(3,TimeUnit.SECONDS);AttachmentRecord offer=p.b.waitFor("offered");p.b.transfers.accept(id).get(3,TimeUnit.SECONDS);p.b.waitFor("failed");p.a.waitFor("failed");
                CoreTests.check(!p.b.persistedBeforeReceipt&&!Files.exists(AttachmentTransfer.file(p.b.root,offer.info)),"Failed save acknowledged or retained");
            }
        });
        CoreTests.test("Attachment record retains metadata and recovers unacknowledged status",()->{
            AttachmentInfo info=new AttachmentInfo(CoreTests.A,"name.pdf","application/pdf",123,"0".repeat(64),42);
            AttachmentRecord record=new AttachmentRecord(info,true,"awaitingReceipt",123);
            CoreTests.check(AttachmentRecord.decode(record.encode()).equals(record),"Metadata did not survive serialization");CoreTests.check(record.recovered().state.equals("unknown"),"Missing receipt was treated as delivery");
            CoreTests.check(AttachmentInfo.safeName("../CON.txt").indexOf('/')<0,"Remote path survived export normalization");
        });
        CoreTests.test("Oversized and overflowing binary chunks are rejected",()->{
            CoreTests.rejects(()->CoreTests.encode(new Frame(Frame.FILE_CHUNK,CoreTests.A,"",1,0,new byte[32769])));
            CoreTests.rejects(()->CoreTests.encode(new Frame(Frame.FILE_CHUNK,CoreTests.A,"",1,Long.MAX_VALUE,new byte[1])));
            CoreTests.rejects(()->new AttachmentInfo(CoreTests.A,"../bad\n","text/plain",0,"0".repeat(64),1));
        });
    }
}
