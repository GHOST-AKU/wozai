package dev.ghost.nearbyim.core;

import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.io.*;

/** Real JDK signatures; no Android or third-party dependencies. */
public final class AuthenticationTests {
    public static void main(String[] args) throws Exception {
        CoreTests.test("Persistent identity signs with its canonical public key", () -> {
            DeviceIdentity identity = DeviceIdentity.generate();
            String encoded = identity.publicKey();
            byte[] keyBytes = Base64.getDecoder().decode(encoded);
            CoreTests.check(encoded.equals(Base64.getEncoder().encodeToString(keyBytes)), "Public key is not canonical Base64");
            PublicKey key = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(keyBytes));
            byte[] message = "this session identity".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] signature = identity.sign(message);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(key); verifier.update(message);
            CoreTests.check(verifier.verify(signature), "Identity cannot prove ownership of its advertised key");
            DeviceIdentity reused = new DeviceIdentity(new KeyPair(key, new NonExportingPrivateKey()));
            CoreTests.check(encoded.equals(reused.publicKey()), "Constructor tried to export a private key");
        });
        CoreTests.test("Only canonical X509 P256 identity keys are accepted", () -> {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp384r1"));
            CoreTests.rejects(() -> new DeviceIdentity(generator.generateKeyPair()));
            generator.initialize(new ECGenParameterSpec("secp256r1")); KeyPair valid = generator.generateKeyPair();
            PublicKey trailing = new PublicKey() {
                public String getAlgorithm() { return "EC"; }
                public String getFormat() { return "X.509"; }
                public byte[] getEncoded() { return Arrays.copyOf(valid.getPublic().getEncoded(), valid.getPublic().getEncoded().length + 1); }
            };
            CoreTests.rejects(() -> new DeviceIdentity(new KeyPair(trailing, valid.getPrivate())));
        });
        CoreTests.test("Verified offer exposes key only after current-session proof", () -> {
            Channels pair = new Channels(); pair.exchangeOffers();
            CoreTests.check(pair.a.key() == null, "Unproven peer key exposed");
            pair.exchangeProofs();
            CoreTests.check(pair.ib.publicKey().equals(pair.a.key()), "Wrong proven peer key");
        });
        CoreTests.test("Old proof cannot authenticate a fresh connection", () -> {
            Channels first = new Channels(); first.exchangeOffers();
            byte[] saved = first.b.proof();
            Channels second = new Channels(first.ia, first.ib); second.exchangeOffers();
            CoreTests.rejects(() -> second.a.acceptProof(saved));
            CoreTests.check(second.a.key() == null, "Replay exposed authenticated key");
        });
        CoreTests.test("Same UUID with a replacement key exposes the replacement identity", () -> {
            Channels first = new Channels(); first.handshake();
            Channels second = new Channels(first.ia, DeviceIdentity.generate()); second.handshake();
            CoreTests.check(!first.a.key().equals(second.a.key()), "UUID hid a different public key");
        });
        CoreTests.test("Altered proof and proof from a different identity are rejected", () -> {
            Channels pair = new Channels(); pair.exchangeOffers();
            byte[] validProof = pair.b.proof(), alteredProof = validProof.clone(); alteredProof[alteredProof.length - 1] ^= 1;
            CoreTests.rejects(() -> pair.a.acceptProof(alteredProof));
            Channels replacement = new Channels(pair.ia, DeviceIdentity.generate()); replacement.exchangeOffers();
            CoreTests.rejects(() -> replacement.a.acceptProof(validProof));
        });
        CoreTests.test("Substituted offer key invalidates the original signed proof", () -> {
            Channels pair = new Channels();
            byte[] original = pair.b.offer(), substituted = original.clone();
            byte[] originalKey = Base64.getDecoder().decode(pair.ib.publicKey());
            byte[] replacementKey = Base64.getDecoder().decode(DeviceIdentity.generate().publicKey());
            int keyOffset = indexOf(substituted, originalKey);
            CoreTests.check(keyOffset >= 0 && originalKey.length == replacementKey.length, "Cannot locate fixed-format P256 key");
            System.arraycopy(replacementKey, 0, substituted, keyOffset, replacementKey.length);
            pair.a.acceptOffer(substituted); pair.b.acceptOffer(pair.a.offer());
            byte[] proof = pair.b.proof();
            CoreTests.rejects(() -> pair.a.acceptProof(proof));
            CoreTests.check(pair.a.key() == null, "Substituted key acquired authenticated identity");
        });
        CoreTests.test("Record body tampering is rejected before frame delivery", () -> {
            Channels pair = new Channels(); pair.handshake();
            byte[] record = pair.b.record(new Frame(Frame.TEXT, CoreTests.B, "authentic body", 1));
            int body = indexOf(record, "authentic body".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CoreTests.check(body >= 0, "Text unexpectedly encrypted"); record[body] ^= 1;
            CoreTests.rejects(() -> pair.a.accept(record));
        });
        CoreTests.test("Duplicate, cross-session and reflected records are rejected", () -> {
            Channels pair = new Channels(); pair.handshake();
            byte[] record = pair.b.record(new Frame(Frame.TEXT, CoreTests.B, "valid", 1));
            CoreTests.check(pair.a.accept(record).body.equals("valid"), "Valid record lost");
            CoreTests.rejects(() -> pair.a.accept(record));
            Channels next = new Channels(pair.ia, pair.ib); next.handshake();
            CoreTests.rejects(() -> next.a.accept(record));
            byte[] reflected = next.a.record(new Frame(Frame.READY, "", "", 1));
            CoreTests.rejects(() -> next.a.accept(reflected));
        });
        CoreTests.test("Invalid authenticated envelope lengths reject before allocation", () -> {
            Channels pair = new Channels();
            CoreTests.rejects(() -> pair.a.acceptOffer(new byte[]{127, -1, -1, -1}));
            CoreTests.rejects(() -> pair.a.acceptOffer(new byte[]{0, 0, 0, 0}));
        });
        CoreTests.test("Offer without a valid proof never reaches consent listener", () -> {
            try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                 java.net.Socket raw = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                raw.setSoTimeout(2000); SessionTests.Events events = new SessionTests.Events();
                FramedSession session = new FramedSession(SessionTests.connection(server.accept()), CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start();
                    AuthenticatedChannel peer = new AuthenticatedChannel(DeviceIdentity.generate(), new Frame(Frame.HELLO, CoreTests.B, "假朋友", 1));
                    peer.readOffer(raw.getInputStream()); peer.writeOffer(raw.getOutputStream());
                    peer.readProof(raw.getInputStream());
                    CoreTests.check(session.remotePublicKey() == null && events.hello.isEmpty(), "Unproven offer disclosed authenticated identity");
                    raw.shutdownOutput();
                    CoreTests.check(events.closed.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null && events.hello.isEmpty(), "Missing proof accepted");
                } finally { session.close("cleanup"); }
            }
        });
        CoreTests.test("Legacy unsigned greeting never reaches consent listener", () -> {
            try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                 java.net.Socket raw = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort())) {
                SessionTests.Events events = new SessionTests.Events();
                FramedSession session = new FramedSession(SessionTests.connection(server.accept()), CoreTests.A, "本机", DeviceIdentity.generate(), events);
                try {
                    session.start();
                    Protocol.write(raw.getOutputStream(), new Frame(Frame.HELLO, CoreTests.B, "伪装者", 1));
                    CoreTests.check(events.closed.poll(2, java.util.concurrent.TimeUnit.SECONDS) != null
                                    && events.hello.isEmpty() && session.remotePublicKey() == null,
                            "Unsigned identity reached consent listener or was not refused");
                } finally { session.close("cleanup"); }
            }
        });
        System.out.println("Authentication result: " + CoreTests.passed + " passed, " + CoreTests.failed + " failed");
        if (CoreTests.failed != 0) System.exit(1);
    }
    private static int indexOf(byte[] bytes, byte[] expected) {
        for (int i = 0; i <= bytes.length - expected.length; i++) {
            boolean match = true;
            for (int j = 0; j < expected.length; j++) if (bytes[i + j] != expected[j]) { match = false; break; }
            if (match) return i;
        }
        return -1;
    }
    private static final class Channels {
        final DeviceIdentity ia, ib;
        final Channel a, b;
        Channels() throws Exception { this(DeviceIdentity.generate(), DeviceIdentity.generate()); }
        Channels(DeviceIdentity ia, DeviceIdentity ib) throws Exception {
            this.ia = ia; this.ib = ib;
            a = new Channel(ia, new Frame(Frame.HELLO, CoreTests.A, "甲", 1));
            b = new Channel(ib, new Frame(Frame.HELLO, CoreTests.B, "乙", 1));
        }
        void exchangeOffers() throws Exception { a.acceptOffer(b.offer()); b.acceptOffer(a.offer()); }
        void exchangeProofs() throws Exception { byte[] pa = a.proof(), pb = b.proof(); a.acceptProof(pb); b.acceptProof(pa); }
        void handshake() throws Exception { exchangeOffers(); exchangeProofs(); }
    }
    private static final class Channel {
        final AuthenticatedChannel implementation;
        Channel(DeviceIdentity identity, Frame hello) throws IOException { implementation = new AuthenticatedChannel(identity, hello); }
        byte[] offer() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); implementation.writeOffer(out); return out.toByteArray();
        }
        byte[] proof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); implementation.writeProof(out); return out.toByteArray();
        }
        byte[] record(Frame frame) throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); implementation.write(out, frame); return out.toByteArray();
        }
        void acceptOffer(byte[] bytes) throws IOException { implementation.readOffer(new ByteArrayInputStream(bytes)); }
        void acceptProof(byte[] bytes) throws IOException { implementation.readProof(new ByteArrayInputStream(bytes)); }
        Frame accept(byte[] bytes) throws IOException { return implementation.read(new ByteArrayInputStream(bytes)); }
        String key() { return implementation.remotePublicKey(); }
    }
    private static final class NonExportingPrivateKey implements PrivateKey {
        public String getAlgorithm() { return "EC"; }
        public String getFormat() { throw new AssertionError("Private key must not be exported"); }
        public byte[] getEncoded() { throw new AssertionError("Private key must not be exported"); }
    }
}
