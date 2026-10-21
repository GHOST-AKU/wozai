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
        boolean ui = args[0].equals("ui-server");
        boolean initiator = args[0].equals("client");
        if (initiator) {
            try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(args[1]))) { exchange(socket, true, false); }
        } else {
            try (ServerSocket listener = new ServerSocket(ui ? 47831 : 0, 1, InetAddress.getByName("127.0.0.1"))) {
                listener.setSoTimeout(ui ? 300_000 : 30_000);
                Files.writeString(Path.of(args[1]), Integer.toString(listener.getLocalPort()));
                try (Socket socket = listener.accept()) { exchange(socket, false, ui); }
            }
        }
        System.out.println("PASS: production Java / Apple NIM4, root proofs, consent, bidirectional text/ACK, safety code, file refusal, heartbeat");
    }

    private static void exchange(Socket socket, boolean initiator, boolean ui) throws Exception {
        socket.setSoTimeout(ui ? 90_000 : 20_000); socket.setTcpNoDelay(true);
        StreamConnection connection = new StreamConnection() {
            public InputStream input() throws IOException { return socket.getInputStream(); }
            public OutputStream output() throws IOException { return socket.getOutputStream(); }
            public String label() { return "synthetic loopback interop peer"; }
            public void close() throws IOException { socket.close(); }
        };
        DeviceIdentity identity = DeviceIdentity.generate();
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        try (NoiseRecordChannel channel = new NoiseRecordChannel(connection, initiator, identity,
                UUID.randomUUID().toString(), "Java interop peer", key, null, null, false, false)) {
            channel.establish(); require(channel.verified(), "Unauthenticated channel");
            String safety = new SafetyCode(identity.publicKey(), channel.remotePublicKey()).hex();
            channel.write(ProtocolV4.encode(new Frame(Frame.READY, "", "", 0)));
            Frame ready = ProtocolV4.decode(channel.read()); require(ready.type == Frame.READY, "Missing consent READY");
            String textID = UUID.randomUUID().toString();
            channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, textID, "Hello from Java" + (ui ? "" : "\n" + safety), 1234)));
            String fileID = UUID.randomUUID().toString(), source = UUID.randomUUID().toString();
            TransferPacket offer = TransferPacket.offer(AttachmentInfo.v2(fileID, "interop.txt", "text/plain", 3, null, 1234), source, channel.connectionGeneration());
            channel.write(ProtocolV4.encodeTransfer(offer));
            channel.write(ProtocolV4.encode(new Frame(Frame.PING, "", "", 0)));
            boolean receivedText = false, receivedACK = false, rejectedFile = false, receivedPong = false;
            for (int records = 0; records < 32 && !(receivedText && receivedACK && rejectedFile && receivedPong); records++) {
                ProtocolV4.Record record = ProtocolV4.decodeRecord(channel.read());
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
                        require(!receivedText && frame.body.equals("Hello from Apple" + (ui ? "" : "\n" + safety)), "Text or safety-code mismatch");
                        receivedText = true;
                        channel.write(ProtocolV4.encode(new Frame(Frame.ACK, frame.id, "", 0)));
                        break;
                    case Frame.ACK: require(frame.id.equals(textID), "Wrong text ACK"); receivedACK = true; break;
                    case Frame.PONG: receivedPong = true; break;
                    case Frame.PING: channel.write(ProtocolV4.encode(new Frame(Frame.PONG, "", "", 0))); break;
                    default: throw new AssertionError("Unexpected frame: " + frame.type);
                }
            }
            require(receivedText && receivedACK && rejectedFile && receivedPong, "Incomplete exchange");
            if (ui) { exerciseReadingPosition(channel); }
            channel.write(ProtocolV4.encode(new Frame(Frame.BYE, "", "", 0)));
        }
    }

    /** An out-of-band test driver injects real encrypted messages while the UI is reading history. */
    private static void exerciseReadingPosition(NoiseRecordChannel channel) throws Exception {
        Thread receipts = new Thread(() -> {
            try {
                while (true) {
                    Frame frame = ProtocolV4.decode(channel.read());
                    if (frame.type == Frame.PING) { channel.write(ProtocolV4.encode(new Frame(Frame.PONG, "", "", 0))); }
                    else { require(frame.type == Frame.ACK || frame.type == Frame.PONG, "Unexpected UI fixture response"); }
                }
            } catch (IOException closed) { /* The main test closes the independent peer after completion. */ }
        }, "apple-ui-receipts");
        receipts.setDaemon(true);
        receipts.start();
        try (ServerSocket control = new ServerSocket(47832, 1, InetAddress.getByName("127.0.0.1"))) {
            control.setSoTimeout(90_000);
            long timestamp = System.currentTimeMillis();
            while (true) {
                try (Socket driver = control.accept()) {
                    driver.setSoTimeout(5_000);
                    String command = new BufferedReader(new InputStreamReader(driver.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)).readLine();
                    if ("populate".equals(command)) {
                        for (int index = 0; index < 80; index++) {
                            channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, UUID.randomUUID().toString(),
                                String.format(java.util.Locale.ROOT, "Reading message %02d\nPreserve this reading position when new messages arrive.", index), ++timestamp)));
                        }
                    } else if ("append".equals(command)) {
                        channel.write(ProtocolV4.encode(new Frame(Frame.TEXT, UUID.randomUUID().toString(), "A new message while reading history", ++timestamp)));
                    } else { require("finish".equals(command), "Unknown UI driver command"); }
                    driver.getOutputStream().write("ok\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if ("finish".equals(command)) { return; }
                }
            }
        }
    }

    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
