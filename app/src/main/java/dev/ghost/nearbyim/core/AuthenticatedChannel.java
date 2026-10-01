package dev.ghost.nearbyim.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Authentication and integrity only: frame bodies remain visible on the transport. */
final class AuthenticatedChannel {
    static final int MAGIC = 0x4e494d32, VERSION = 2, OFFER = 1, PROOF = 2, RECORD = 3;
    static final int NONCE_BYTES = 32, MAX_KEY_BYTES = 256, MAX_SIGNATURE_BYTES = 80;
    static final int MAX_OFFER_BYTES = 512, MAX_PROOF_BYTES = 96, MAX_RECORD_BYTES = 9344;
    private static final int MAX_HELLO_BYTES = 190, MAX_INNER_FRAME_BYTES = 9220;
    private static final byte[] SESSION_DOMAIN = domain("wozai-session-v2"),
            PROOF_DOMAIN = domain("wozai-proof-v2"), RECORD_DOMAIN = domain("wozai-record-v2");
    private final DeviceIdentity identity;
    private final Frame localHello;
    private final byte[] localOffer;
    private byte[] outgoingBinding, incomingBinding;
    private Frame remoteHello;
    private PublicKey remoteKey;
    private String encodedRemoteKey;
    private volatile boolean verified;
    private boolean proofWritten;
    private long outgoingSequence, incomingSequence;

    AuthenticatedChannel(DeviceIdentity identity, Frame hello) throws IOException {
        this.identity = Objects.requireNonNull(identity); localHello = Objects.requireNonNull(hello);
        if (hello.type != Frame.HELLO) throw new IOException("Expected greeting");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream payload = header(bytes, OFFER);
        writeBytes(payload, encode(hello));
        writeBytes(payload, Base64.getDecoder().decode(identity.publicKey()));
        byte[] nonce = new byte[NONCE_BYTES]; new SecureRandom().nextBytes(nonce); payload.write(nonce);
        localOffer = bytes.toByteArray();
    }

    void writeOffer(OutputStream output) throws IOException { writeEnvelope(output, localOffer); }

    void readOffer(InputStream input) throws IOException {
        if (remoteHello != null) throw new IOException("Repeated greeting");
        byte[] offer = readEnvelope(input, OFFER);
        DataInputStream payload = payload(offer);
        Frame hello = decode(readBytes(payload, MAX_HELLO_BYTES));
        if (hello.type != Frame.HELLO || hello.id.equalsIgnoreCase(localHello.id)) throw new IOException("Invalid greeting");
        byte[] keyBytes = readBytes(payload, MAX_KEY_BYTES);
        byte[] nonce = new byte[NONCE_BYTES]; payload.readFully(nonce); end(payload);
        try {
            remoteKey = DeviceIdentity.decodePublicKey(keyBytes);
            outgoingBinding = binding(localOffer, offer); incomingBinding = binding(offer, localOffer);
        } catch (GeneralSecurityException error) { throw new IOException("Invalid peer identity", error); }
        encodedRemoteKey = Base64.getEncoder().encodeToString(keyBytes); remoteHello = hello;
    }

    void writeProof(OutputStream output) throws IOException {
        if (outgoingBinding == null || proofWritten) throw new IOException("Invalid proof state");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream payload = header(bytes, PROOF);
        writeBytes(payload, sign(proof(outgoingBinding))); writeEnvelope(output, bytes.toByteArray());
        proofWritten = true;
    }

    void readProof(InputStream input) throws IOException {
        if (remoteHello == null || verified) throw new IOException("Invalid proof state");
        DataInputStream payload = payload(readEnvelope(input, PROOF));
        byte[] signature = readBytes(payload, MAX_SIGNATURE_BYTES); end(payload);
        verify(proof(incomingBinding), signature); verified = true;
    }

    Frame remoteHello() { return verified ? remoteHello : null; }
    String remotePublicKey() { return verified ? encodedRemoteKey : null; }

    // Called only by the session's single writer; input is read by its single reader.
    void write(OutputStream output, Frame frame) throws IOException {
        if (!verified || !proofWritten || outgoingSequence == Long.MAX_VALUE) throw new IOException("Unauthenticated write");
        if (frame.type == Frame.HELLO) throw new IOException("Repeated greeting");
        byte[] encoded = encode(frame);
        byte[] signature = sign(record(outgoingBinding, outgoingSequence, encoded));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream payload = header(bytes, RECORD);
        payload.writeLong(outgoingSequence); writeBytes(payload, encoded); writeBytes(payload, signature);
        writeEnvelope(output, bytes.toByteArray()); outgoingSequence++;
    }

    Frame read(InputStream input) throws IOException {
        if (!verified || incomingSequence == Long.MAX_VALUE) throw new IOException("Unauthenticated read");
        DataInputStream payload = payload(readEnvelope(input, RECORD));
        long sequence = payload.readLong();
        if (sequence != incomingSequence) throw new IOException("Unexpected record sequence");
        byte[] encoded = readBytes(payload, MAX_INNER_FRAME_BYTES);
        byte[] signature = readBytes(payload, MAX_SIGNATURE_BYTES); end(payload);
        verify(record(incomingBinding, sequence, encoded), signature);
        Frame frame = decode(encoded);
        if (frame.type == Frame.HELLO) throw new IOException("Repeated greeting");
        incomingSequence++; return frame;
    }

    private byte[] sign(byte[] message) throws IOException {
        try {
            byte[] signature = identity.sign(message);
            if (signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES) throw new IOException("Invalid signature size");
            return signature;
        } catch (GeneralSecurityException error) { throw new IOException("Unable to prove identity", error); }
    }
    private void verify(byte[] message, byte[] signature) throws IOException {
        try {
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(remoteKey); verifier.update(message);
            if (!verifier.verify(signature)) throw new IOException("Peer signature does not verify");
        } catch (GeneralSecurityException error) { throw new IOException("Invalid peer proof", error); }
    }
    private static byte[] domain(String text) { return (text + "\0").getBytes(StandardCharsets.US_ASCII); }
    private static byte[] binding(byte[] sender, byte[] receiver) throws GeneralSecurityException, IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.write(SESSION_DOMAIN); writeBytes(out, sender); writeBytes(out, receiver);
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
    }
    private static byte[] proof(byte[] binding) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); bytes.write(PROOF_DOMAIN); bytes.write(binding); return bytes.toByteArray();
    }
    private static byte[] record(byte[] binding, long sequence, byte[] frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.write(RECORD_DOMAIN); out.write(binding); out.writeLong(sequence); writeBytes(out, frame); return bytes.toByteArray();
    }
    private static DataOutputStream header(ByteArrayOutputStream bytes, int kind) throws IOException {
        DataOutputStream out = new DataOutputStream(bytes); out.writeInt(MAGIC); out.writeByte(VERSION); out.writeByte(kind); return out;
    }
    private static void writeEnvelope(OutputStream output, byte[] bytes) throws IOException {
        DataOutputStream out = new DataOutputStream(output); out.writeInt(bytes.length); out.write(bytes); out.flush();
    }
    private static byte[] readEnvelope(InputStream input, int kind) throws IOException {
        DataInputStream in = new DataInputStream(input); int size = in.readInt();
        int max = kind == OFFER ? MAX_OFFER_BYTES : kind == PROOF ? MAX_PROOF_BYTES : MAX_RECORD_BYTES;
        if (size < 6 || size > max) throw new IOException("Invalid authenticated envelope length");
        if (in.readInt() != MAGIC || in.readUnsignedByte() != VERSION || in.readUnsignedByte() != kind)
            throw new IOException("Unsupported authenticated protocol");
        byte[] bytes = new byte[size];
        bytes[0] = (byte) (MAGIC >>> 24); bytes[1] = (byte) (MAGIC >>> 16); bytes[2] = (byte) (MAGIC >>> 8); bytes[3] = (byte) MAGIC;
        bytes[4] = (byte) VERSION; bytes[5] = (byte) kind; in.readFully(bytes, 6, size - 6); return bytes;
    }
    private static DataInputStream payload(byte[] bytes) { return new DataInputStream(new ByteArrayInputStream(bytes, 6, bytes.length - 6)); }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] readBytes(DataInputStream in, int max) throws IOException {
        int size = in.readInt();
        if (size <= 0 || size > max || size > in.available()) throw new IOException("Invalid authenticated field length");
        byte[] bytes = new byte[size]; in.readFully(bytes); return bytes;
    }
    private static void end(DataInputStream in) throws IOException { if (in.available() != 0) throw new IOException("Trailing authenticated data"); }
    private static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Protocol.write(out, frame); return out.toByteArray();
    }
    private static Frame decode(byte[] bytes) throws IOException {
        ByteArrayInputStream input = new ByteArrayInputStream(bytes); Frame frame = Protocol.read(input);
        if (input.available() != 0) throw new IOException("Trailing inner frame data"); return frame;
    }
}
