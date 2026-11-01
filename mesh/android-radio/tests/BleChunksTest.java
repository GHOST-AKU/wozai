package org.nearbymesh.radio;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/** Host JVM tests; no Android classes or radio hardware are needed. */
public final class BleChunksTest {
    public static void main(String[] args) {
        defaultMtuPreservesBytes();
        negotiatedMtuPreservesBytes();
        characteristicLimitIsRespected();
        inputAndOutputAreIndependent();
        invalidInputsAreRejected();
        exhaustionDoesNotFabricateBytes();
        System.out.println("BleChunksTest: 6 passed");
    }

    private static void defaultMtuPreservesBytes() {
        byte[] bytes = new byte[41];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i - 20);
        List<byte[]> chunks = drain(new BleChunks(bytes, 23));
        check(chunks.size() == 3, "MTU23 needs three chunks for41 bytes");
        check(chunks.get(0).length == 20, "default ATT payload must be20");
        check(chunks.get(1).length == 20 && chunks.get(2).length == 1,
                "default MTU tail must preserve its exact length");
        check(Arrays.equals(bytes, join(chunks)), "default MTU must preserve every byte");
    }

    private static void negotiatedMtuPreservesBytes() {
        byte[] bytes = new byte[4096];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        List<byte[]> chunks = drain(new BleChunks(bytes, 247));
        check(chunks.size() == 17, "4096 bytes need seventeen244-byte chunks");
        check(chunks.get(0).length == 244 && chunks.get(16).length == 192,
                "MTU247 must allow244-byte payload and exact final192 bytes");
        check(Arrays.equals(bytes, join(chunks)), "full core read must survive fragmentation");
        check(!new BleChunks(new byte[0], 23).hasNext(), "empty input sends no ATT write");
    }

    private static void characteristicLimitIsRespected() {
        List<byte[]> chunks = drain(new BleChunks(new byte[1025], 517));
        check(chunks.size() == 3 && chunks.get(0).length == 512
                && chunks.get(1).length == 512 && chunks.get(2).length == 1,
                "ATT values must never exceed512 bytes even at MTU517");
    }

    private static void inputAndOutputAreIndependent() {
        byte[] bytes = new byte[] {1, 2, 3};
        byte[] chunk = new BleChunks(bytes, 23).next();
        bytes[0] = 9;
        check(chunk[0] == 1, "queued chunks must own their source bytes");
        chunk[1] = 8;
        check(bytes[1] == 2, "changing a queued chunk must not mutate the core buffer");
    }

    private static void invalidInputsAreRejected() {
        rejects(() -> new BleChunks(new byte[4097], 23), "core read limit");
        rejects(() -> new BleChunks(new byte[1], 22), "invalid MTU below23");
        rejects(() -> new BleChunks(new byte[1], 518), "invalid MTU above517");
        rejects(() -> new BleChunks(null, 23), "null input");
    }

    private static void exhaustionDoesNotFabricateBytes() {
        BleChunks chunks = new BleChunks(new byte[] {7}, 23);
        check(chunks.hasNext() && Arrays.equals(chunks.next(), new byte[] {7}), "one-byte tail must be returned once");
        check(!chunks.hasNext(), "cursor must finish exactly at the end of the input");
        try { chunks.next(); } catch (NoSuchElementException expected) { return; }
        throw new AssertionError("exhausted cursor must not return another ATT value");
    }

    private static List<byte[]> drain(BleChunks cursor) {
        List<byte[]> chunks = new ArrayList<>();
        while (cursor.hasNext()) chunks.add(cursor.next());
        return chunks;
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
