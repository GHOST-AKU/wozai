package org.nearbymesh.radio;

import java.util.Arrays;
import java.util.NoSuchElementException;

/** ATT fragmentation of the existing core byte stream, without an extra wire header. */
final class BleChunks {
    static final int DEFAULT_MTU = 23;
    static final int MAX_MTU = 517;
    static final int MAX_CORE_CHUNK = 4096;

    private final byte[] bytes;
    private final int payload;
    private int offset;

    /** The caller owns bytes and keeps it unchanged until all fragments are acknowledged. */
    BleChunks(byte[] bytes, int mtu) {
        if (bytes == null || bytes.length > MAX_CORE_CHUNK)
            throw new IllegalArgumentException("ble_core_chunk_bound");
        this.bytes = bytes;
        this.payload = payloadSize(mtu);
    }

    boolean hasNext() { return offset < bytes.length; }

    byte[] next() {
        if (!hasNext()) throw new NoSuchElementException("ble_chunks_exhausted");
        int end = Math.min(bytes.length, offset + payload);
        byte[] chunk = Arrays.copyOfRange(bytes, offset, end);
        offset = end;
        return chunk;
    }

    static int payloadSize(int mtu) {
        if (mtu < DEFAULT_MTU || mtu > MAX_MTU)
            throw new IllegalArgumentException("ble_mtu_bound");
        return Math.min(mtu - 3, 512);
    }
}
