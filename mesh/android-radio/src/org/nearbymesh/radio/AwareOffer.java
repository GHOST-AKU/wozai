package org.nearbymesh.radio;

import java.nio.charset.StandardCharsets;

/**
 * Bounded, untrusted discovery coordination for the isolated radio lab.
 * Labels and attempt tokens select a candidate only; Rust authenticates every peer.
 * Socket addresses and ports must come from the Aware Network, never this envelope.
 */
final class AwareOffer {
    static final int HELLO = 1;
    static final int READY = 2;
    private static final String PREFIX = "NM-AWARE/1";
    private static final int MAX_BYTES = 64;

    final int kind;
    final String label;
    final String target;
    final long token;

    AwareOffer(int kind, String label, String target, long token) {
        if ((kind != HELLO && kind != READY) || !validLabel(label) || !validLabel(target)) {
            throw new IllegalArgumentException("invalid Aware discovery offer");
        }
        this.kind = kind;
        this.label = label;
        this.target = target;
        this.token = token;
    }

    byte[] encode() {
        String hex = Long.toHexString(token);
        StringBuilder value = new StringBuilder(48);
        value.append(PREFIX).append('|').append(kind == HELLO ? 'H' : 'R')
                .append('|').append(label).append('|').append(target).append('|');
        for (int i = hex.length(); i < 16; ++i) value.append('0');
        value.append(hex);
        return value.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Returns null for unknown versions and every malformed or oversized envelope. */
    static AwareOffer parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        for (byte value : bytes) {
            if (value < 0x20 || value > 0x7e) return null;
        }
        String[] fields = new String(bytes, StandardCharsets.US_ASCII).split("\\|", -1);
        if (fields.length != 5 || !PREFIX.equals(fields[0])) return null;
        int kind;
        if ("H".equals(fields[1])) kind = HELLO;
        else if ("R".equals(fields[1])) kind = READY;
        else return null;
        if (!validLabel(fields[2]) || !validLabel(fields[3]) || fields[4].length() != 16) {
            return null;
        }
        long token = 0L;
        for (int i = 0; i < 16; ++i) {
            char value = fields[4].charAt(i);
            int digit;
            if (value >= '0' && value <= '9') digit = value - '0';
            else if (value >= 'a' && value <= 'f') digit = value - 'a' + 10;
            else return null;
            token = (token << 4) | digit;
        }
        return new AwareOffer(kind, fields[2], fields[3], token);
    }

    private static boolean validLabel(String value) {
        if (value == null || value.length() < 1 || value.length() > 8) return false;
        for (int i = 0; i < value.length(); ++i) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z')
                    && !(c >= '0' && c <= '9') && c != '_' && c != '-') return false;
        }
        return true;
    }
}
