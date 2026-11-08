package org.nearbymesh.radio;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host checks for the untrusted discovery envelope, not peer authentication. */
public final class AwareOfferTest {
    private static int checks;

    public static void main(String[] args) {
        roundTrips();
        rejectsMalformed();
        rejectsInvalidConstruction();
        System.out.println("AwareOfferTest: " + checks + " checks passed");
    }

    private static void roundTrips() {
        long[] tokens = {0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (int kind : new int[] {AwareOffer.HELLO, AwareOffer.READY}) {
            for (long token : tokens) {
                AwareOffer original = new AwareOffer(kind, "A_0-x", "BCDEF123", token);
                byte[] encoded = original.encode();
                check(encoded.length <= 64, "offer exceeds bounded envelope");
                AwareOffer parsed = AwareOffer.parse(encoded);
                check(parsed != null, "valid envelope rejected");
                check(parsed.kind == kind && parsed.label.equals("A_0-x")
                        && parsed.target.equals("BCDEF123") && parsed.token == token,
                        "round trip changed fields");
            }
        }
        check(Arrays.equals(ascii("NM-AWARE/1|H|A|B|0000000000000001"),
                new AwareOffer(AwareOffer.HELLO, "A", "B", 1L).encode()),
                "wire format changed");
    }

    private static void rejectsMalformed() {
        check(AwareOffer.parse(null) == null, "null accepted");
        check(AwareOffer.parse(new byte[0]) == null, "empty accepted");
        check(AwareOffer.parse(new byte[65]) == null, "oversized accepted");
        String[] invalid = {
            "NM-AWARE/2|H|A|B|0000000000000001",
            "NM-AWARE/01|H|A|B|0000000000000001",
            "NM-AWARE/1|X|A|B|0000000000000001",
            "NM-AWARE/1|HELLO|A|B|0000000000000001",
            "NM-AWARE/1|H||B|0000000000000001",
            "NM-AWARE/1|H|A||0000000000000001",
            "NM-AWARE/1|H|123456789|B|0000000000000001",
            "NM-AWARE/1|H|A|123456789|0000000000000001",
            "NM-AWARE/1|H|a.b|B|0000000000000001",
            "NM-AWARE/1|H|A|B |0000000000000001",
            "NM-AWARE/1|H|A|B|1",
            "NM-AWARE/1|H|A|B|00000000000000001",
            "NM-AWARE/1|H|A|B|000000000000000G",
            "NM-AWARE/1|H|A|B|FFFFFFFFFFFFFFFF",
            "NM-AWARE/1|H|A|B|0000000000000001|",
            "NM-AWARE/1|H|A|B|0000000000000001|extra",
            "NM-AWARE/1|H|A|B|0000000000000001\n",
            "NM-AWARE/1|H|A|B|0000000000000001\u0000",
            "NM-AWARE/1|H|A|B"
        };
        for (String input : invalid) {
            check(AwareOffer.parse(ascii(input)) == null, "malformed accepted: " + input);
        }
        check(AwareOffer.parse("NM-AWARE/1|H|é|B|0000000000000001"
                .getBytes(StandardCharsets.UTF_8)) == null, "non-ASCII accepted");
        byte[] highBit = ascii("NM-AWARE/1|H|A|B|0000000000000001");
        highBit[12] = (byte) 0xff;
        check(AwareOffer.parse(highBit) == null, "invalid UTF-8 accepted");
    }

    private static void rejectsInvalidConstruction() {
        rejects(() -> new AwareOffer(3, "A", "B", 0L));
        rejects(() -> new AwareOffer(AwareOffer.HELLO, null, "B", 0L));
        rejects(() -> new AwareOffer(AwareOffer.HELLO, "", "B", 0L));
        rejects(() -> new AwareOffer(AwareOffer.HELLO, "A", "A B", 0L));
        rejects(() -> new AwareOffer(AwareOffer.HELLO, "A", "123456789", 0L));
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static void rejects(Runnable action) {
        try {
            action.run();
            throw new AssertionError("invalid constructor accepted");
        } catch (IllegalArgumentException expected) {
            ++checks;
        }
    }

    private static void check(boolean condition, String message) {
        ++checks;
        if (!condition) throw new AssertionError(message);
    }
}
