package dev.ghost.nearbyim.core;

import dev.ghost.nearbyim.noise.NoiseRecordChannel;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.UUID;

/** Uses the shipping Java codecs and Noise channel, independently of the Swift implementation. */
public final class AppleInteropPeer {
    public static void main(String[] args) throws Exception {
        boolean reads=java.util.Arrays.asList(args).contains("--reads")||java.util.Arrays.asList(args).contains("--read-ui");
        boolean ui = args[0].equals("ui-server");
        boolean initiator = args[0].equals("client");
        PreparedPeer prepared=new PreparedPeer(initiator,ui);
        if (initiator) {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(args[1]))) { exchange(socket, true, false, reads,prepared); }
        } else {
            try (ServerSocket listener = new ServerSocket(ui ? 47831 : 0, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(ui ? 300_000 : 30_000);
                Files.writeString(Path.of(args[1]), Integer.toString(listener.getLocalPort()));
                try (Socket socket = listener.accept()) { exchange(socket, false, ui, reads,prepared); }
            }
        }
        System.out.println("PASS: production Java / Apple NIM4, root proofs, consent, bidirectional text/ACK, safety code, file refusal, heartbeat");
    }

    /** Complete server identity/static-key preparation before publishing the listening port. */
    private static final class PreparedPeer implements StreamConnection {
        final DeviceIdentity identity;
        final NoiseRecordChannel channel;
        final boolean trace;
        volatile Socket socket;
        volatile boolean authenticated;
        long began;
        PreparedPeer(boolean initiator,boolean ui)throws Exception {
            long setup=System.nanoTime();trace=ui;identity=DeviceIdentity.generate();
            byte[] key=new byte[32];new SecureRandom().nextBytes(key);
            channel=new NoiseRecordChannel(this,initiator,identity,UUID.randomUUID().toString(),ui?"Java interop peer":"\u00a0",key,null,null,false,false);
            java.util.Arrays.fill(key,(byte)0);
            if(trace)System.out.println("UI peer preparation_ms="+(System.nanoTime()-setup)/1_000_000.0+"; ready published afterward");
        }
        private Socket connected()throws IOException {Socket value=socket;if(value==null)throw new IOException("Fixture used before accept");return value;}
        private void sample(String direction,int bytes){if(trace&&!authenticated)System.out.println("UI handshake "+direction+" bytes="+bytes+" elapsed_ms="+(System.nanoTime()-began)/1_000_000.0);}
        public InputStream input()throws IOException {return new FilterInputStream(connected().getInputStream()){
            public int read()throws IOException {int value=in.read();sample("read",value<0?-1:1);return value;}
            public int read(byte[] data,int from,int count)throws IOException {int bytes=in.read(data,from,count);sample("read",bytes);return bytes;}
        };}
        public OutputStream output()throws IOException {return new FilterOutputStream(connected().getOutputStream()){
            public void write(int value)throws IOException {out.write(value);sample("write",1);}
            public void write(byte[] data,int from,int count)throws IOException {out.write(data,from,count);sample("write",count);}
        };}
        public String label(){return "synthetic loopback interop peer";}
        public void close()throws IOException {Socket value=socket;if(value!=null)value.close();}
    }

    private static void exchange(Socket socket, boolean initiator, boolean ui, boolean reads,PreparedPeer prepared) throws Exception {
        socket.setSoTimeout(ui ? 90_000 : 20_000); socket.setTcpNoDelay(true);
        prepared.socket=socket;prepared.began=System.nanoTime();
        DeviceIdentity identity=prepared.identity;
        try (ServerSocket control=ui?new ServerSocket(47832,1,InetAddress.getByName("127.0.0.1")):null;
             NoiseRecordChannel channel=prepared.channel) {
            channel.establish(); require(channel.verified(), "Unauthenticated channel");
            prepared.authenticated=true;
            if(ui)System.out.println("UI handshake verified_ms="+(System.nanoTime()-prepared.began)/1_000_000.0);
            String safety = new SafetyCode(identity.publicKey(), channel.remotePublicKey()).hex();
            channel.write(ProtocolV4.encode(new Frame(Frame.READY, "", "", 0)));
            Frame ready = ProtocolV4.decode(channel.read()); require(ready.type == Frame.READY, "Missing consent READY");
            TextCapability capacity=new TextCapability(channel.connectionGeneration(),time->{try{channel.write(ProtocolV4.encode(new Frame(Frame.PONG,"","",time)));}catch(IOException error){throw new java.io.UncheckedIOException(error);}},()->{});
            java.util.Set<String> observed=new java.util.LinkedHashSet<>(),ownReads=java.util.concurrent.ConcurrentHashMap.newKeySet();
            ReadReceiptCapability readCapability=new ReadReceiptCapability(channel.connectionGeneration(),time->{try{channel.write(ProtocolV4.encode(new Frame(Frame.PONG,"","",time)));}catch(IOException error){throw new java.io.UncheckedIOException(error);}},(packet,current)->{try{if(current.getAsBoolean())channel.write(ProtocolV4.encode(new Frame(Frame.READ_RECEIPT,"","",0,0,packet.encode())));return true;}catch(IOException error){throw new java.io.UncheckedIOException(error);}},()->{});
            if(reads){readCapability.configure(true);readCapability.start();}
            if(!ui)capacity.start();
            String longID=UUID.randomUUID().toString(),longBody="中".repeat(10922)+"ab";boolean longSent=ui,longReceived=ui,longAck=ui;
            String textID = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT), whitespaceID = UUID.randomUUID().toString();
            channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, textID, "Hello from Java" + (ui ? "" : "\n" + safety), 1234)));
            if (!ui) { channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, whitespaceID, "\u00a0\u2003", 1235))); }
            String fileID = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
            TransferPacket offer = TransferPacket.offer(AttachmentInfo.v2(fileID, "interop.txt", "text/plain", 3, null, 1234), source, channel.connectionGeneration());
            channel.write(ProtocolV4.encodeTransfer(offer));
            // A user pause may already be queued when the text-only peer refuses the offer.
            channel.write(ProtocolV4.encodeTransfer(TransferPacket.control(TransferPacket.Kind.PAUSE,
                fileID, source, channel.connectionGeneration(), true, 3, 0, 0, null, "")));
            channel.write(ProtocolV4.encodeTransfer(TransferPacket.control(TransferPacket.Kind.STATUS,
                fileID, source, channel.connectionGeneration(), true, 3, 0, 0, null, "")));
            channel.write(ProtocolV4.encode(new Frame(Frame.PING, "", "", 0)));
            boolean receivedText = false, receivedACK = false, whitespaceACK = ui, rejectedFile = false, receivedPong = false;
            for (int records = 0; records < 64 && !(receivedText && receivedACK && whitespaceACK && rejectedFile && receivedPong && longReceived && longAck && (!reads||ui||(ownReads.containsAll(java.util.List.of(textID.toLowerCase(java.util.Locale.ROOT),whitespaceID,longID))&&observed.isEmpty()))); records++) {
                ProtocolV4.Record record = ProtocolV4.decodeRecord(channel.read(),capacity.incoming());
                if (record.transfer != null) {
                    TransferPacket packet = record.transfer;
                    require(packet.kind == TransferPacket.Kind.CANCEL && !packet.fromSender && packet.reason.equals("rejected")
                        && packet.transferId.equals(fileID) && packet.sourceGeneration.equals(source)
                        && packet.connectionGeneration.equals(channel.connectionGeneration()), "Invalid file refusal");
                    rejectedFile = true;
                    continue;
                }
                Frame frame = record.frame; require(frame != null, "Unexpected record family");
                switch (frame.type) {
                    case Frame.TEXT:
                        if(reads&&!ui)observed.add(frame.id.toLowerCase(java.util.Locale.ROOT));
                        if(frame.body.equals(longBody)&&!ui){require(!longReceived,"Duplicate long text");longReceived=true;}
                        else{require(!receivedText && frame.body.equals("Hello from Apple" + (ui ? "" : "\n" + safety)), "Text or safety-code mismatch");receivedText=true;}
                        channel.write(ProtocolV4.encode(new Frame(Frame.ACK, frame.id, "", 0)));
                        break;
                    case Frame.ACK:
                        if(frame.id.equals(longID)&&!ui){longAck=true;}
                        else if (frame.id.equals(textID)) { receivedACK = true; }
                        else { require(!ui && frame.id.equals(whitespaceID), "Wrong text ACK"); whitespaceACK = true; }
                        break;
                    case Frame.READ_RECEIPT:
                        require(reads,"Unexpected negotiated read record");ReadReceiptPacket read=ReadReceiptPacket.decode(frame.data);
                        if(readCapability.receive(read)){
                            if(read.kind==ReadReceiptPacket.READ){ownReads.addAll(read.ids);readCapability.acknowledge(read);}
                            else{observed.removeAll(read.ids);readCapability.persisted(read);}
                        }break;
                    case Frame.PONG:
                        if(reads)readCapability.receiveToken(frame.timestamp);
                        if(frame.timestamp==0)receivedPong=true;
                        if(!ui){capacity.receive(frame.timestamp);if(!longSent&&capacity.outgoing()==32768){longSent=true;channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,longID,longBody,1236),capacity.outgoing()));}}
                        break;
                    case Frame.PING: channel.write(ProtocolV4.encode(new Frame(Frame.PONG, "", "", 0))); break;
                    default: throw new AssertionError("Unexpected frame: " + frame.type);
                }
                if(reads&&readCapability.active()&&!observed.isEmpty())readCapability.send(new java.util.ArrayList<>(observed));
            }
            if(reads&&!ui){require(ownReads.containsAll(java.util.List.of(textID.toLowerCase(java.util.Locale.ROOT),whitespaceID,longID))&&observed.isEmpty(),"Incomplete bidirectional read receipts");System.out.println("PASS: Java/Swift negotiated read receipts, both directions, exact UUIDs and durable ACK retirement");}
            require(receivedText && receivedACK && whitespaceACK && rejectedFile && receivedPong && longReceived && longAck, "Incomplete exchange");
            if (ui) { exerciseReadingPosition(channel,reads?readCapability:null,ownReads,textID.toLowerCase(java.util.Locale.ROOT),control); }
            channel.write(ProtocolV4.encode(new Frame(Frame.BYE, "", "", 0)));
        }
    }

    /** An out-of-band test driver injects real encrypted messages while the UI is reading history. */
    private static void exerciseReadingPosition(NoiseRecordChannel channel,ReadReceiptCapability readCapability,java.util.Set<String> ownReads,String initialID,ServerSocket control) throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> readProbe=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger acknowledgements = new java.util.concurrent.atomic.AtomicInteger();
        Path fileRoot = Files.createTempDirectory("wozai-ui-files-");
        byte[] fileBytes = new byte[3 * TransferLimits.BLOCK_BYTES + 17];
        for (int i = 0; i < fileBytes.length; i++) fileBytes[i] = (byte)(i * 31 + 7);
        Path fileSource = fileRoot.resolve("UI-attachment.bin"); Files.write(fileSource, fileBytes);
        java.util.concurrent.BlockingQueue<AttachmentRecord> fileNotices = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> readFailure = new java.util.concurrent.atomic.AtomicReference<>();
        AttachmentTransferV2 files = new AttachmentTransferV2(fileRoot.resolve("received"), "a".repeat(64),
            DeviceIdentity.fingerprint(channel.remotePublicKey()), channel.connectionGeneration(), false,
            new AttachmentTransferV2.Wire() {
                public boolean send(TransferPacket packet) { try { channel.write(ProtocolV4.encodeTransfer(packet)); return true; } catch (IOException error) { readFailure.set(error); return false; } }
                public void abort() { try { channel.close(); } catch (Exception ignored) {} }
            }, fileNotices::add);
        Thread receipts = new Thread(() -> {
            try {
                while (true) {
                    ProtocolV4.Record record = ProtocolV4.decodeRecord(channel.read());
                    if (record.transfer != null) { files.receive(record.transfer, true); continue; }
                    Frame frame = record.frame;
                    if(frame.type==Frame.READ_RECEIPT&&readCapability!=null){ReadReceiptPacket packet=ReadReceiptPacket.decode(frame.data);if(readCapability.receive(packet)){if(packet.kind==ReadReceiptPacket.READ){ownReads.addAll(packet.ids);readCapability.acknowledge(packet);}else readCapability.persisted(packet);}continue;}
                    if(frame.type==Frame.PONG&&readCapability!=null)readCapability.receiveToken(frame.timestamp);
                    if (frame.type == Frame.PING) { channel.write(ProtocolV4.encode(new Frame(Frame.PONG, "", "", 0))); }
                    else {
                        require(frame.type == Frame.ACK || frame.type == Frame.PONG, "Unexpected UI fixture response");
                        if (frame.type == Frame.ACK) { acknowledgements.incrementAndGet(); }
                    }
                }
            } catch (Throwable closed) { readFailure.compareAndSet(null, closed); }
        }, "apple-ui-receipts");
        receipts.setDaemon(true);
        receipts.start();
        try {
            control.setSoTimeout(90_000);
            // A skewed peer clock must not hide subsequent normal-time messages from the latest page.
            long timestamp = System.currentTimeMillis() + java.util.concurrent.TimeUnit.DAYS.toMillis(3650);
            int fixtureIndex = 0, appended = 0, historyCount = 2;
            while (true) {
                try (Socket driver = control.accept()) {
                    driver.setSoTimeout(5_000);
                    String command = new BufferedReader(new InputStreamReader(driver.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine();
                    if("read-initial-check".equals(command)||"read-visible-check".equals(command)){
                        String id=command.equals("read-initial-check")?initialID:readProbe.get();
                        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                        while(!ownReads.contains(id)&&System.nanoTime()<end){if(readFailure.get()!=null)throw new IOException("Read UI fixture failed",readFailure.get());Thread.sleep(20);}
                        require(ownReads.contains(id),"Actually visible native Apple text did not emit read receipt");
                    }else if("read-hidden-send".equals(command)){
                        String id=UUID.randomUUID().toString();readProbe.set(id);
                        channel.write(ProtocolV4.encode(new Frame(Frame.TEXT,id,"Read receipt hidden probe",System.currentTimeMillis())));
                    }else if("read-hidden-check".equals(command)){
                        Thread.sleep(650);require(!ownReads.contains(readProbe.get()),"Hidden Apple conversation emitted a read receipt");
                    }else if ("file-offer".equals(command)) {
                        files.offer(new FileAttachmentSource(fileSource), "UI-attachment.bin", "application/octet-stream").get(5, java.util.concurrent.TimeUnit.SECONDS);
                    } else if ("file-saved".equals(command)) {
                        boolean saved = false;
                        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
                        while (!saved && System.nanoTime() < deadline) {
                            if (readFailure.get() != null) throw new IOException("File UI peer failed", readFailure.get());
                            AttachmentRecord notice = fileNotices.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                            if (notice != null) {
                                require(!notice.state.equals("failed") && !notice.state.equals("canceled"), "File UI transfer failed");
                                saved = notice.state.equals("delivered");
                            }
                        }
                        require(saved, "UI receiver did not return its durable saved receipt");
                    } else if ("history-200".equals(command) || "history-400".equals(command)) {
                        int target = command.equals("history-200") ? 200 : 400;
                        require(historyCount < target, "History boundary was already passed");
                        while (historyCount < target) {
                            historyCount++;
                            channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, UUID.randomUUID().toString(), "History boundary " + historyCount, ++timestamp)));
                        }
                    } else if ("populate".equals(command)) {
                        for (int index = 0; index < 80; index++) {
                            channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, UUID.randomUUID().toString(),
                                String.format(java.util.Locale.ROOT, "Reading message %02d\nPreserve this reading position when new messages arrive.", fixtureIndex++), ++timestamp)));
                        }
                        historyCount += 80;
                        System.out.println("UI fixture: sent 80 reading messages; total=" + fixtureIndex);
                    } else if ("append".equals(command)) {
                        historyCount++;
                        channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, UUID.randomUUID().toString(), "A new message while reading history " + (++appended), System.currentTimeMillis())));
                    } else { require("finish".equals(command), "Unknown UI driver command"); }
                    System.out.println("UI driver: " + command + ", durable ACKs=" + acknowledgements.get());
                    driver.getOutputStream().write("ok\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if ("finish".equals(command)) { return; }
                }
            }
        } finally {
            files.shutdown().get(5, java.util.concurrent.TimeUnit.SECONDS);
            try (var paths = Files.walk(fileRoot)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }

    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
