package org.nearbymesh.radio;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

/** Host JVM tests; no Android classes or radio hardware are needed. */
public final class BleChunksTest {
    public static void main(String[] args) {
        defaultMtuPreservesBytes();
        negotiatedMtuPreservesBytes();
        characteristicLimitIsRespected();
        inputAndOutputAreIndependent();
        invalidInputsAreRejected();
        System.out.println("BleChunksTest: 5 passed");
    }

    private static void defaultMtuPreservesBytes() {
        byte[] bytes = new byte[41];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i - 20);
        List<byte[]> chunks = BleChunks.split(bytes, 23);
        check(chunks.size() == 3, "MTU23 needs three chunks for41 bytes");
        check(chunks.get(0).length == 20, "default ATT payload must be20");
        check(chunks.get(1).length == 20 && chunks.get(2).length == 1,
                "default MTU tail must preserve its exact length");
        check(Arrays.equals(bytes, join(chunks)), "default MTU must preserve every byte");
    }

    private static void negotiatedMtuPreservesBytes() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        List<byte[]> chunks = BleChunks.split(bytes, 247);
        check(chunks.size() == 17, "4096 bytes need seventeen244-byte chunks");
        check(chunks.get(0).length == 244 && chunks.get(16).length == 192,
                "MTU247 must allow244-byte payload and exact final192 bytes");
        check(Arrays.equals(bytes, join(chunks)), "full core read must survive fragmentation");
        check(BleChunks.split(new byte[0], 23).isEmpty(), "empty input sends no ATT write");
    }

    private static void characteristicLimitIsRespected() {
        List<byte[]> chunks = BleChunks.split(new byte[1025], 517);
        check(chunks.size() == 3 && chunks.get(0).length == 512
                && chunks.get(1).length == 512 && chunks.get(2).length == 1,
                "ATT values must never exceed512 bytes even at MTU517");
    }

    private static void inputAndOutputAreIndependent() {
        byte[] bytes = new byte[] {1, 2, 3};
        List<byte[]> chunks = BleChunks.split(bytes, 23);
        bytes[0] = 9;
        check(chunks.get(0)[0] == 1, "queued chunks must own their source bytes");
        chunks.get(0)[1] = 8;
        check(bytes[1] == 2, "changing a queued chunk must not mutate the core buffer");
    }

    private static void invalidInputsAreRejected() {
        rejects(() -> BleChunks.split(new byte[4097], 23), "core read limit");
        rejects(() -> BleChunks.split(new byte[1], 22), "invalid MTU below23");
        rejects(() -> BleChunks.split(new byte[1], 518), "invalid MTU above517");
        rejects(() -> BleChunks.split(null, 23), "null input");
    }

    private static byte[] join(List<byte[]> chunks) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) result.write(chunk, 0, chunk.length);
        return result.toByteArray();
    }

    private static void rejects(Runnable operation, String reason) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("must reject " + reason);
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
