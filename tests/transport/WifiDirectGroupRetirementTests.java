package dev.ghost.nearbyim.transport;

import java.lang.reflect.*;

/** Native snapshots are inputs; neither command ACK nor removal ACK proves group absence. */
public final class WifiDirectGroupRetirementTests {
    private static Class<?> type;
    private static int checks;
    public static void main(String[] args) throws Exception {
        try { type = Class.forName("dev.ghost.nearbyim.transport.WifiDirectPolicy$GroupRetirement"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("A known-group retry must confirm retirement instead of treating its own residual group as foreign", missing); }
        Object owner = target(true, "p2p0");
        check(state(owner, "DIRECT-ab-wozai-123456", "secret-password", true, "p2p0", 100).equals("REMOVE"), "Exact own GO credentials and interface permit one removal");
        type.getDeclaredMethod("removing").invoke(owner);
        check(state(owner, "DIRECT-ab-wozai-123456", "secret-password", true, "p2p0", 101).equals("WAIT"), "Removal submission and ACK cannot reopen the next attempt");
        check(state(owner, "DIRECT-ab-wozai-123456", "secret-password", true, "p2p0", 3999).equals("WAIT"), "An unchanged group is still retiring");
        check(state(owner, "DIRECT-ab-wozai-123456", "secret-password", true, "p2p0", 4000).equals("EXPIRED"), "Repeated snapshots cannot extend the retirement budget");
        check(state(owner, null, null, false, null, 4000).equals("ABSENT"), "An actual absent group releases the retry fence");
        for (Object[] foreign : new Object[][] {
                {"DIRECT-ab-other", "secret-password", true, "p2p0"},
                {"DIRECT-ab-wozai-123456", "wrong-password", true, "p2p0"},
                {"DIRECT-ab-wozai-123456", null, true, "p2p0"},
                {"DIRECT-ab-wozai-123456", "secret-password", false, "p2p0"},
                {"DIRECT-ab-wozai-123456", "secret-password", true, "p2p1"}}) {
            check(state(target(true, "p2p0"), (String)foreign[0], (String)foreign[1], (boolean)foreign[2], (String)foreign[3], 100).equals("FOREIGN"), "Foreign role/name/secret/interface never grants global removal authority");
        }
        Object client = target(false, "p2p0");
        check(state(client, "DIRECT-ab-wozai-123456", null, false, "p2p0", 100).equals("REMOVE"), "An ACKed explicit join may disconnect its exact observed membership without reading a GO secret");
        check(state(target(true, null), "DIRECT-ab-wozai-123456", "secret-password", true, null, 100).equals("WAIT"), "An incomplete interface snapshot is not removal authority");
        check(state(target(true, null), "DIRECT-ab-wozai-123456", "secret-password", true, "p2p0", 100).equals("REMOVE"), "A canceled accepted create can retire its subsequently formed exact credential group");
        System.out.println("P2P known-group retirement: " + checks + " checks passed");
    }
    private static Object target(boolean owner, String iface) throws Exception {
        return type.getDeclaredConstructor(String.class, String.class, boolean.class, String.class, long.class)
                .newInstance("DIRECT-ab-wozai-123456", "secret-password", owner, iface, 4000L);
    }
    private static String state(Object target, String name, String secret, boolean owner, String iface, long now) throws Exception {
        return type.getDeclaredMethod("observe", String.class, String.class, boolean.class, String.class, long.class)
                .invoke(target, name, secret, owner, iface, now).toString();
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
