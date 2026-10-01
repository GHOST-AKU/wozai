package dev.ghost.nearbyim.core;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class CoreTests {
    static final String A = "11111111-1111-4111-8111-111111111111";
    static final String B = "22222222-2222-4222-8222-222222222222";
    interface Test { void run() throws Exception; }
    static int passed, failed;
    static void test(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable e) { failed++; System.out.println("FAIL " + name + ": " + e); }
    }
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void rejects(Test test) throws Exception {
        try { test.run(); } catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
    static byte[] encode(Frame frame) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); Protocol.write(out, frame); return out.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        test("Unicode and newline survive one-byte reads", () -> {
            byte[] data = encode(new Frame(Frame.TEXT, A, "你好\nCiallo～(∠・ω< )⌒★ 🕷️", 42));
            InputStream fragmented = new ByteArrayInputStream(data) {
                public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
            };
            Frame decoded = Protocol.read(fragmented);
            check(decoded.body.equals("你好\nCiallo～(∠・ω< )⌒★ 🕷️") && decoded.timestamp == 42 && decoded.id.equals(A), "Content corrupted");
        });
        test("Back-to-back frames preserve message boundaries", () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Protocol.write(out, new Frame(Frame.TEXT, A, "first", 1));
            Protocol.write(out, new Frame(Frame.ACK, A, "", 2));
            ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
            check(Protocol.read(in).body.equals("first") && Protocol.read(in).type == Frame.ACK && in.available() == 0, "Framing lost");
        });
        test("Oversized and blank messages rejected", () -> {
            rejects(() -> encode(new Frame(Frame.TEXT, A, "x".repeat(8193), 1)));
            rejects(() -> encode(new Frame(Frame.TEXT, A, "  \n", 1)));
            check(Protocol.read(new ByteArrayInputStream(encode(new Frame(Frame.TEXT, A, "x".repeat(8192), 1)))).body.length() == 8192, "Boundary rejected");
        });
        test("Untrusted frame lengths rejected before allocation", () -> {
            rejects(() -> Protocol.read(new ByteArrayInputStream(new byte[]{127, -1, -1, -1})));
            rejects(() -> Protocol.read(new ByteArrayInputStream(new byte[]{-1, -1, -1, -1})));
            rejects(() -> Protocol.read(new ByteArrayInputStream(new byte[]{0, 0, 0, 0})));
        });
        test("Truncated frame rejected", () -> {
            byte[] data = encode(new Frame(Frame.TEXT, A, "test", 1));
            rejects(() -> Protocol.read(new ByteArrayInputStream(Arrays.copyOf(data, data.length - 1))));
        });
        test("Malformed UTF-8 and unsupported version rejected", () -> {
            final byte[] data = encode(new Frame(Frame.TEXT, A, "test", 1)); data[data.length - 1] = (byte) 0xff;
            rejects(() -> Protocol.read(new ByteArrayInputStream(data)));
            final byte[] wrongVersion = encode(new Frame(Frame.TEXT, A, "test", 1)); wrongVersion[8] = 99;
            rejects(() -> Protocol.read(new ByteArrayInputStream(wrongVersion)));
        });
        test("Unknown types and invalid identities rejected", () -> {
            rejects(() -> encode(new Frame(99, A, "hi", 1)));
            rejects(() -> encode(new Frame(Frame.TEXT, "invalid", "hi", 1)));
            rejects(() -> encode(new Frame(Frame.HELLO, A, "\n", 1)));
            rejects(() -> encode(new Frame(Frame.ACK, A, "not empty", 1)));
        });
        if (args.length == 0 || !args[0].equals("protocol")) SessionTests.run();
        System.out.println("Result: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }
}
