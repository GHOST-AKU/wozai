package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** The selected FILE lane, rather than its chat parent, determines flow control. */
public final class AttachmentRouteWindowTests {
    public static void main(String[] args)throws Exception {
        if(args.length>0&&args[0].equals("--benchmark")){benchmark();return;}
        try(Pair p=new Pair()) {
            p.wifi.set(true);
            p.transfer(1048576);
        }
        try(Pair p=new Pair()) {
            p.transfer(65536);
            p.wifi.set(true);
            p.transfer(1048576);
            p.wifi.set(false);
            p.transfer(65536);
        }
        try(Pair p=new Pair()){p.resumeUpgrade();}
        System.out.println("FILE flow control follows initial selection and idle Bluetooth/Wi-Fi route changes; saved content and receipts verified");
    }
    private static void benchmark()throws Exception {
        for(boolean wifi:new boolean[]{false,true})for(int round=0;round<3;round++)try(Pair p=new Pair()){
            p.wifi.set(wifi);p.delayCreditMs=40;
            byte[] content=new byte[8*1048576+17];new Random(57).nextBytes(content);
            Path source=p.root.resolve("source.bin");Files.write(source,content);
            long start=System.nanoTime();p.engines[0].offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            p.verify(content,15);double seconds=(System.nanoTime()-start)/1e9;
            System.out.printf(Locale.ROOT,"Controlled credit delay: profile=%s, round=%d, delay_ms=40, bytes=%d, seconds=%.6f, MiB_s=%.6f; synthetic pipeline, not RF speed%n",wifi?"wifi":"bluetooth",round+1,content.length,seconds,content.length/1048576.0/seconds);
        }
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static final class Pair implements AutoCloseable {
        final Path root=Files.createTempDirectory("file-route-window-");
        final AtomicBoolean wifi=new AtomicBoolean();
        final AttachmentTransferV2[] engines=new AttachmentTransferV2[2];
        final BlockingQueue<TransferPacket> accepted=new LinkedBlockingQueue<>(),credits=new LinkedBlockingQueue<>();
        final BlockingQueue<AttachmentRecord> received=new LinkedBlockingQueue<>(),delivered=new LinkedBlockingQueue<>();
        final AtomicLong sent=new AtomicLong();
        final AtomicReference<Throwable> failure=new AtomicReference<>();
        final ScheduledExecutorService delayed=Executors.newSingleThreadScheduledExecutor();
        volatile int delayCreditMs;
        volatile boolean holdCredits;
        Pair()throws Exception {
            for(int i=0;i<2;i++) {
                final int to=1-i;
                engines[i]=new AttachmentTransferV2(root.resolve("p"+i),String.valueOf((char)('a'+i)).repeat(64),String.valueOf((char)('a'+to)).repeat(64),AttachmentV2Tests.CONNECTION,true,new AttachmentTransferV2.Wire(){
                    public boolean highBandwidth(){return wifi.get();}
                    public boolean send(TransferPacket packet){
                        if(packet.kind==TransferPacket.Kind.ACCEPT||packet.kind==TransferPacket.Kind.RESUME)accepted.add(packet);
                        if(packet.kind==TransferPacket.Kind.DATA)sent.addAndGet(packet.data.length);
                        if(packet.kind==TransferPacket.Kind.CREDIT)synchronized(credits){if(holdCredits){credits.add(packet);return true;}}
                        if(packet.kind==TransferPacket.Kind.CREDIT&&delayCreditMs>0){delayed.schedule(()->{try{engines[to].receive(packet,true);}catch(IOException error){failure.compareAndSet(null,error);}},delayCreditMs,TimeUnit.MILLISECONDS);return true;}
                        try{engines[to].receive(packet,true);return true;}catch(IOException error){failure.compareAndSet(null,error);return false;}
                    }
                    public void abort(){failure.compareAndSet(null,new IOException("Pipeline aborted"));}
                },record->{if(record.state.equals("received"))received.add(record);if(record.state.equals("delivered"))delivered.add(record);});
            }
        }
        void transfer(long expectedWindow)throws Exception {
            byte[] content=new byte[2*1048576+17];new Random(57).nextBytes(content);
            Path source=root.resolve("source.bin");Files.write(source,content);sent.set(0);credits.clear();holdCredits=true;
            engines[0].offer(new FileAttachmentSource(source),"source.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            TransferPacket accept=accepted.poll(3,TimeUnit.SECONDS);
            check(accept!=null&&accept.windowBytes==expectedWindow,"Selected "+(wifi.get()?"Wi-Fi":"Bluetooth")+" FILE lane advertised "+(accept==null?"no":accept.windowBytes)+" bytes; expected "+expectedWindow);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(sent.get()<expectedWindow&&failure.get()==null&&System.nanoTime()<deadline)Thread.sleep(2);
            check(sent.get()==expectedWindow,"Sender did not fill exactly the advertised byte window before any CREDIT");
            // Publish queued credits before enabling live delivery, preserving cumulative offsets.
            synchronized(credits){holdCredits=false;TransferPacket packet;while((packet=credits.poll())!=null)engines[0].receive(packet,true);}
            verify(content,5);
        }
        void resumeUpgrade()throws Exception {
            byte[] content=new byte[2*1048576+17];new Random(79).nextBytes(content);
            Path source=root.resolve("resume.bin");Files.write(source,content);holdCredits=true;
            String id=engines[0].offer(new FileAttachmentSource(source),"resume.bin","application/octet-stream").get(3,TimeUnit.SECONDS);
            TransferPacket accept=accepted.poll(3,TimeUnit.SECONDS);check(accept!=null&&accept.windowBytes==65536,"Resume fixture must start with Bluetooth limits");
            // Only the sender is manually paused; its peer is quiesced by the route loss.
            engines[0].pause(id,true).get(3,TimeUnit.SECONDS);engines[1].pauseOnDisconnect(id,false).get(3,TimeUnit.SECONDS);
            wifi.set(true);credits.clear();
            engines[0].resume(engines[0].taskIdentity(id,accept.sourceGeneration),new FileAttachmentSource(source,accept.sourceGeneration)).get(3,TimeUnit.SECONDS);
            TransferPacket resume=accepted.poll(3,TimeUnit.SECONDS);
            check(resume!=null&&resume.kind==TransferPacket.Kind.RESUME&&resume.windowBytes==1048576,"Resume after a Wi-Fi upgrade retained Bluetooth flow control");
            synchronized(credits){holdCredits=false;TransferPacket packet;while((packet=credits.poll())!=null)engines[0].receive(packet,true);}
            verify(content,5);
        }
        void verify(byte[] content,int seconds)throws Exception {
            AttachmentRecord saved=received.poll(seconds,TimeUnit.SECONDS),ack=delivered.poll(seconds,TimeUnit.SECONDS);
            check(failure.get()==null&&saved!=null&&ack!=null,"Selected-lane transfer failed: "+failure.get());
            check(Arrays.equals(content,Files.readAllBytes(AttachmentTransfer.file(root.resolve("p1"),saved.info))),"Received bytes changed");
            check(saved.info.hash.equals(ack.info.hash),"Saved receipt digest changed");
        }
        public void close()throws Exception {
            delayed.shutdownNow();
            for(AttachmentTransferV2 engine:engines)if(engine!=null)engine.shutdown().get(5,TimeUnit.SECONDS);
            try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
