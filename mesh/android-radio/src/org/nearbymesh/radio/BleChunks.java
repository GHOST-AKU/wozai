package org.nearbymesh.radio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** ATT fragmentation of the existing core byte stream, without an extra wire header. */
final class BleChunks {
    static final int DEFAULT_MTU = 23;
    static final int MAX_MTU = 517;
    static final int MAX_CORE_CHUNK = 4096;

    private BleChunks() {}

    static List<byte[]> split(byte[] bytes, int mtu) {
        if (bytes == null || bytes.length > MAX_CORE_CHUNK)
            throw new IllegalArgumentException("ble_core_chunk_bound");
        int payload = payloadSize(mtu);
        List<byte[]> chunks = new ArrayList<>((bytes.length + payload - 1) / payload);
        for (int offset = 0; offset < bytes.length; offset += payload)
            chunks.add(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + payload)));
        return chunks;
    }

    static int payloadSize(int mtu) {
        if (mtu < DEFAULT_MTU || mtu > MAX_MTU)
            throw new IllegalArgumentException("ble_mtu_bound");
        return Math.min(mtu - 3, 512);
    }
}
