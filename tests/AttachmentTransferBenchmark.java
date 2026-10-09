package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.i18n.UiText;
import java.io.*;
import java.net.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback benchmark. Raw mode measures payload only; signed mode commits real files. */
public final class AttachmentTransferBenchmark {
    private static final long MAX_SIZE=10_737_418_240L;
    private static final int BUFFER=64*1024;
    private static final long TIMEOUT_SECONDS=1800;
    private record Source(Path path,byte[] hash,long generationNs) {}
    private static final class Round {
        volatile long start,prepared,first,last,commitStart,committed,receipt;
        long setup;
        Map<String,Object> report(long size,String mode,byte[] hash) {
            Map<String,Object> result=new LinkedHashMap<>();
            result.put("mode",mode);result.put("size_bytes",size);result.put("sha256",AttachmentInfo.hex(hash));result.put("verified",true);
            result.put("connection_setup_ns",setup);result.put("prepare_ns",Math.max(0,prepared-start));
            result.put("first_byte_ns",first==0?null:Math.max(0,first-start));
            long transfer=first==0?0:Math.max(1,last-first);
            result.put("transfer_ns",transfer);result.put("final_commit_ns",Math.max(0,committed-commitStart));
            result.put("receipt_ns",Math.max(0,receipt-committed));result.put("total_ns",Math.max(1,receipt-start));
            result.put("steady_mib_s",size==0?null:rate(size,transfer));result.put("total_mib_s",size==0?null:rate(size,receipt-start));
            result.put("commit_kind",mode.equals("raw-tcp")?"network-only-no-file-commit":"file-force-atomic-move-record-force");
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        Map<String,String> options=new HashMap<>();
        Set<String> keys=Set.of("mode","size","rounds","output","environment-kind","source-commit","artifact-sha256","work-dir");
        for(int i=0;i<args.length;i+=2) {
            if(!args[i].startsWith("--")||i+1==args.length||!keys.contains(args[i].substring(2))||options.put(args[i].substring(2),args[i+1])!=null)
                throw new IllegalArgumentException("Unknown, missing or duplicate benchmark argument");
        }
        String mode=options.getOrDefault("mode","signed-v3");
        if(!Set.of("raw-tcp","signed-v3","file-v2").contains(mode))throw new IllegalArgumentException("Benchmark mode not implemented: "+mode);
        long size=Long.parseLong(options.getOrDefault("size","50331648"));
        int rounds=Integer.parseInt(options.getOrDefault("rounds","3"));
        if(size<0||size>MAX_SIZE||mode.equals("signed-v3")&&size>AttachmentInfo.MAX_SIZE||rounds<1||rounds>20)
            throw new IllegalArgumentException("Invalid size/round count (signed-v3 retains its 1 GiB limit)");
        String kind=options.getOrDefault("environment-kind","virtual");
        if(!kind.equals("virtual"))throw new IllegalArgumentException("This loopback runner only measures a virtual environment");
        Path destination=Path.of(options.getOrDefault("output","build/transfer-benchmark.json")).toAbsolutePath();
        Path working=Path.of(options.getOrDefault("work-dir",System.getProperty("java.io.tmpdir"))).toAbsolutePath();Files.createDirectories(working);
        long required=size*(mode.equals("signed-v3")?3:2)+64L*1024*1024;
        if(Files.getFileStore(working).getUsableSpace()<required)throw new IOException("Benchmark work directory needs "+required+" free bytes; choose --work-dir");
        Path root=Files.createTempDirectory(working,"wozai-transfer-benchmark-");
        try {
            Source source=generate(root.resolve("source.bin"),size);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("schema_version",1);report.put("environment_kind",kind);report.put("transport","TCP loopback, both endpoints in one JVM");
            report.put("work_directory",working.toString());report.put("file_store_type",Files.getFileStore(root).type());
            report.put("source_commit",options.getOrDefault("source-commit","unknown"));report.put("artifact_sha256",options.getOrDefault("artifact-sha256","unknown"));
            report.put("artifact_kind","compiled-core-classes");report.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
            report.put("java_version",System.getProperty("java.version"));report.put("max_heap_bytes",Runtime.getRuntime().maxMemory());
            report.put("source_generation_ns",source.generationNs);report.putAll(diskBaseline(root,source,size));
            List<Map<String,Object>> samples=new ArrayList<>();
            for(int i=0;i<rounds;i++) {
                Round sample=switch(mode){case "raw-tcp"->raw(source,size);case "file-v2"->v2(root.resolve("round-"+i),source,size);default->signed(root.resolve("round-"+i),source,size);};
                Map<String,Object> data=sample.report(size,mode,source.hash);data.put("round",i+1);samples.add(data);
            }
            report.put("rounds",samples);
            report.put("limitations","Loopback/JIT/container results are not phone, radio, cross-client or 10 GiB acceptance. Source generation is reported separately; signed-v3 preparation is included in total. Raw mode does not save received content. file-v2 is a controlled plaintext test Wire, not an enabled client protocol or encryption acceptance.");
            publish(destination,json(report));
            System.out.println("Verified "+rounds+" "+mode+" rounds, "+size+" bytes each; report: "+destination);
        } finally {deleteTree(root);}
    }

    private static Source generate(Path path,long size) throws Exception {
        long start=System.nanoTime();byte[] buffer=new byte[BUFFER];new Random(0x032).nextBytes(buffer);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(FileOutputStream out=new FileOutputStream(path.toFile())) {
            for(long offset=0;offset<size;) {int n=(int)Math.min(buffer.length,size-offset);out.write(buffer,0,n);digest.update(buffer,0,n);offset+=n;}
            out.getChannel().force(true);
        }
        return new Source(path,digest.digest(),System.nanoTime()-start);
    }
    private static Map<String,Object> diskBaseline(Path root,Source source,long size) throws Exception {
        byte[] buffer=new byte[BUFFER];long readStart=System.nanoTime(),readBytes=0;
        try(InputStream input=Files.newInputStream(source.path)) {int n;while((n=input.read(buffer))!=-1)readBytes+=n;}
        long readNs=System.nanoTime()-readStart;
        if(readBytes!=size)throw new IOException("Disk source truncated");
        Path target=root.resolve("disk-baseline.bin");long writeStart=System.nanoTime();
        try(FileOutputStream output=new FileOutputStream(target.toFile())) {
            for(long offset=0;offset<size;) {int n=(int)Math.min(buffer.length,size-offset);output.write(buffer,0,n);offset+=n;}
            output.getChannel().force(true);
        }
        long writeNs=System.nanoTime()-writeStart;
        if(Files.size(target)!=size)throw new IOException("Disk target truncated");
        Files.delete(target);Map<String,Object> result=new LinkedHashMap<>();
        result.put("source_read_ns",readNs);result.put("source_read_mib_s",size==0?null:rate(size,readNs));
        result.put("sync_write_ns",writeNs);result.put("sync_write_mib_s",size==0?null:rate(size,writeNs));
        result.put("disk_baseline_kind","warm-cache sequential read; sequential write plus force(true), same temporary filesystem");return result;
    }
    private static Round raw(Source source,long size) throws Exception {
        Round round=new Round();long setup=System.nanoTime();ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket sender=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket receiver=server.accept()) {
            sender.setSoTimeout((int)TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));receiver.setSoTimeout((int)TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            round.setup=System.nanoTime()-setup;round.start=System.nanoTime();round.prepared=round.start;
            Future<?> receiving=worker.submit(()->{
                try {
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[BUFFER];long received=0;
                    InputStream input=receiver.getInputStream();
                    while(received<size) {int n=input.read(buffer,0,(int)Math.min(buffer.length,size-received));if(n<0)throw new EOFException("Truncated raw payload");if(n==0)continue;if(round.first==0)round.first=System.nanoTime();digest.update(buffer,0,n);received+=n;}
                    round.last=System.nanoTime();byte[] actual=digest.digest();requireDigest(source.hash,actual,size,received);
                    round.commitStart=round.committed=System.nanoTime();DataOutputStream ack=new DataOutputStream(receiver.getOutputStream());ack.writeLong(received);ack.write(actual);ack.flush();
                } catch(Exception error) {try{receiver.close();}catch(IOException ignored){}throw new CompletionException(error);}
            });
            try(InputStream input=Files.newInputStream(source.path)) {OutputStream output=sender.getOutputStream();byte[] buffer=new byte[BUFFER];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);output.flush();}
            DataInputStream receipt=new DataInputStream(sender.getInputStream());long received=receipt.readLong();byte[] hash=new byte[32];receipt.readFully(hash);round.receipt=System.nanoTime();requireDigest(source.hash,hash,size,received);
            receiving.get(TIMEOUT_SECONDS,TimeUnit.SECONDS);return round;
        } finally {worker.shutdownNow();}
    }
    private static final class Endpoint implements FramedSession.Listener,AutoCloseable {
        final Path root;final Round timing;final boolean sender;
        final CountDownLatch hello=new CountDownLatch(1),ready=new CountDownLatch(1);
        final CompletableFuture<AttachmentRecord> complete=new CompletableFuture<>();
        FramedSession session;AttachmentTransfer transfer;
        Endpoint(Path root,Round timing,boolean sender){this.root=root;this.timing=timing;this.sender=sender;}
        void init(Socket socket,String id) throws Exception {
            session=new FramedSession(SessionTests.connection(socket),id,"benchmark",DeviceIdentity.generate(),this);
            transfer=new AttachmentTransfer(root,new AttachmentTransfer.Wire(){public boolean send(Frame frame){return session.sendAttachment(frame);}public void abort(){session.close(UiText.EMPTY);}},record->{
                long now=System.nanoTime();
                if(sender&&record.state.equals("offered"))timing.prepared=now;
                if(!sender&&record.state.equals("verifying"))timing.commitStart=now;
                if(!sender&&record.state.equals("received")) {
                    Path metadata=root.resolve("received.record");
                    try(FileOutputStream out=new FileOutputStream(metadata.toFile())) {out.write(record.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getChannel().force(true);}
                    timing.committed=System.nanoTime();complete.complete(record);
                }
                if(sender&&record.state.equals("delivered")) {timing.receipt=System.nanoTime();complete.complete(record);}
                if(Set.of("failed","interrupted","unknown","canceled","rejected").contains(record.state))complete.completeExceptionally(new IOException("Transfer "+record.state));
            },AttachmentInfo.CHUNK_SIZE,AttachmentInfo.MAX_SIZE);
        }
        public void onHello(Frame frame){hello.countDown();}
        public void onReady(){ready.countDown();}
        public void onText(Frame frame){session.acknowledge(frame.id);}
        public void onAck(String id){}
        public void onClosed(UiText reason){complete.completeExceptionally(new IOException("Benchmark connection closed"));}
        public void onAttachment(Frame frame) {
            if(frame.type==Frame.FILE_CHUNK&&timing.first==0)timing.first=System.nanoTime();
            if(frame.type==Frame.FILE_FINISH)timing.last=System.nanoTime();
            transfer.receive(frame,true);
        }
        public void close() throws Exception {if(transfer!=null)transfer.shutdown().get(5,TimeUnit.SECONDS);if(session!=null)session.close(UiText.EMPTY);}
    }
    private static Round signed(Path root,Source source,long size) throws Exception {
        Round timing=new Round();long setup=System.nanoTime();
        try(Endpoint a=new Endpoint(root.resolve("a"),timing,true);Endpoint b=new Endpoint(root.resolve("b"),timing,false)) {
            try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());a.init(client,CoreTests.A);b.init(server.accept(),CoreTests.B);}
            a.session.start();b.session.start();
            if(!a.hello.await(10,TimeUnit.SECONDS)||!b.hello.await(10,TimeUnit.SECONDS))throw new IOException("Benchmark handshake failed");
            a.session.approve();b.session.approve();if(!a.ready.await(10,TimeUnit.SECONDS)||!b.ready.await(10,TimeUnit.SECONDS))throw new IOException("Benchmark approval failed");
            timing.setup=System.nanoTime()-setup;timing.start=System.nanoTime();
            a.transfer.offer(()->Files.newInputStream(source.path),"benchmark.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            AttachmentRecord received=b.complete.get(TIMEOUT_SECONDS,TimeUnit.SECONDS);a.complete.get(TIMEOUT_SECONDS,TimeUnit.SECONDS);
            Path path=AttachmentTransfer.file(b.root,received.info);requireDigest(source.hash,hashFile(path),size,Files.size(path));
            return timing;
        } finally {deleteTree(root);}
    }
    private static final class V2Endpoint implements AutoCloseable {
        final Path root;final Round timing;final boolean sender;final Socket socket;
        final CompletableFuture<AttachmentRecord> complete=new CompletableFuture<>();
        final AttachmentTransferV2 transfer;
        private final OutputStream output;
        volatile boolean closed;
        V2Endpoint(Path root,Round timing,boolean sender,Socket socket)throws Exception {
            this.root=root;this.timing=timing;this.sender=sender;this.socket=socket;
            output=new BufferedOutputStream(socket.getOutputStream(),64*1024);
            transfer=new AttachmentTransferV2(root,(sender?"a":"b").repeat(64),(sender?"b":"a").repeat(64),"c".repeat(64),false,new AttachmentTransferV2.Wire(){
                public boolean send(TransferPacket packet){synchronized(output){try{TransferCodec.write(output,packet);output.flush();return true;}catch(IOException error){return false;}}}
                public void abort(){try{socket.close();}catch(IOException ignored){}}
            },record->{
                if(sender&&record.state.equals("offered"))timing.prepared=System.nanoTime();
                if(!sender&&record.state.equals("verifying"))timing.commitStart=System.nanoTime();
                if(!sender&&record.state.equals("received")) {
                    try(FileOutputStream out=new FileOutputStream(root.resolve("received.record").toFile())){out.write(record.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getChannel().force(true);}
                    timing.committed=System.nanoTime();complete.complete(record);
                }
                if(sender&&record.state.equals("delivered")){timing.receipt=System.nanoTime();complete.complete(record);}
                if(Set.of("failed","interrupted","canceled").contains(record.state))complete.completeExceptionally(new IOException("v2 transfer "+record.state));
            });
        }
        void start(){Thread reader=new Thread(()->{
            try {InputStream input=new BufferedInputStream(socket.getInputStream(),64*1024);
                while(!closed){TransferPacket packet=TransferCodec.read(input);if(!sender&&packet.kind==TransferPacket.Kind.DATA&&timing.first==0)timing.first=System.nanoTime();if(!sender&&packet.kind==TransferPacket.Kind.END)timing.last=System.nanoTime();transfer.receive(packet,true);}
            }catch(IOException error){if(!closed){complete.completeExceptionally(error);transfer.close();}}
        },"benchmark-v2-reader");reader.setDaemon(true);reader.start();}
        public void close()throws Exception {closed=true;socket.close();transfer.shutdown().get(5,TimeUnit.SECONDS);}
    }
    private static Round v2(Path root,Source source,long size)throws Exception {
        Round timing=new Round();long setup=System.nanoTime();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket client=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort());Socket accepted=server.accept();
            V2Endpoint a=new V2Endpoint(root.resolve("a"),timing,true,client);V2Endpoint b=new V2Endpoint(root.resolve("b"),timing,false,accepted)) {
            a.start();b.start();timing.setup=System.nanoTime()-setup;timing.start=System.nanoTime();
            a.transfer.offer(new FileAttachmentSource(source.path),"benchmark.bin","application/octet-stream").get(10,TimeUnit.SECONDS);
            AttachmentRecord received=b.complete.get(TIMEOUT_SECONDS,TimeUnit.SECONDS);a.complete.get(TIMEOUT_SECONDS,TimeUnit.SECONDS);
            Path file=AttachmentTransfer.file(b.root,received.info);requireDigest(source.hash,hashFile(file),size,Files.size(file));return timing;
        }finally{deleteTree(root);}
    }
    static void requireDigest(byte[] expected,byte[] actual,long expectedSize,long actualSize) throws IOException {
        if(expected.length!=32||actual.length!=32||expectedSize!=actualSize||!MessageDigest.isEqual(expected,actual))throw new IOException("Unverified benchmark result: size/SHA-256 mismatch");
    }
    private static byte[] hashFile(Path path) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[BUFFER];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}return digest.digest();}
    private static double rate(long bytes,long ns){return bytes/(1024.0*1024.0)/(Math.max(1,ns)/1e9);}
    private static void publish(Path path,String body) throws IOException {
        Files.createDirectories(path.getParent());Path temporary=Files.createTempFile(path.getParent(),"benchmark-",".tmp");
        try {Files.writeString(temporary,body+"\n");Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
    }
    private static void deleteTree(Path root) throws IOException {if(!Files.exists(root))return;try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    private static String json(Object value) {
        if(value==null)return "null";
        if(value instanceof Number||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> map){List<String> parts=new ArrayList<>();map.forEach((key,item)->parts.add(json(key.toString())+":"+json(item)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(AttachmentTransferBenchmark::json).toList())+"]";
        StringBuilder text=new StringBuilder("\"");for(char c:value.toString().toCharArray()){if(c=='"'||c=='\\')text.append('\\').append(c);else if(c<32)text.append(String.format("\\u%04x",(int)c));else text.append(c);}return text.append('"').toString();
    }
}
