package org.nearbymesh.radio;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Adapter-only discovery and stream admission. Labels are not authenticated identities. */
final class CocProtocol {
    static final int VERSION = 1, READY = 0x52;
    static final class Offer {
        final String label; final int psm;
        Offer(String label, int psm) { this.label = label; this.psm = psm; }
    }
    static byte[] advertisement(String label, int psm) {
        if (!validLabel(label) || psm < 1 || psm > 65535) throw new IllegalArgumentException("coc_offer");
        byte[] name = label.getBytes(StandardCharsets.US_ASCII), data = new byte[name.length + 4];
        data[0] = VERSION; data[1] = (byte) name.length;
        System.arraycopy(name, 0, data, 2, name.length);
        data[data.length - 2] = (byte) (psm >>> 8); data[data.length - 1] = (byte) psm;
        return data;
    }
    static Offer parse(byte[] data, RadioConfig config) {
        if (data == null || data.length < 5 || data.length > 12 || data[0] != VERSION) return null;
        int length = data[1] & 255;
        if (length < 1 || length > 8 || data.length != length + 4) return null;
        String label = ascii(data, 2, length);
        int psm = ((data[data.length - 2] & 255) << 8) | (data[data.length - 1] & 255);
        return config.accepts(label) && psm != 0 ? new Offer(label, psm) : null;
    }
    static boolean validLabel(String label) { return label != null && label.matches("[A-Za-z0-9_-]{1,8}"); }
    private static String ascii(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) if ((data[i] & 128) != 0) return null;
        return new String(data, offset, length, StandardCharsets.US_ASCII);
    }
    static void writePreface(OutputStream output, String label) throws IOException {
        byte[] name = label.getBytes(StandardCharsets.US_ASCII);
        output.write(new byte[] {'N', 'M', 'L', 'C', VERSION, (byte) name.length});
        output.write(name);
    }
    static String readPreface(InputStream input) throws IOException {
        byte[] header = new byte[6]; readExact(input, header);
        if (header[0] != 'N' || header[1] != 'M' || header[2] != 'L' || header[3] != 'C'
                || header[4] != VERSION || header[5] < 1 || header[5] > 8) throw new IOException("preface_invalid");
        byte[] name = new byte[header[5]]; readExact(input, name);
        String label = ascii(name, 0, name.length);
        if (!validLabel(label)) throw new IOException("preface_invalid");
        return label;
    }
    static void readExact(InputStream input, byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            int count = input.read(data, offset, data.length - offset);
            if (count <= 0) throw new IOException("socket_read");
            offset += count;
        }
    }
    private CocProtocol() { }
}
