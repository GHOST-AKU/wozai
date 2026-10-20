package dev.ghost.nearbyim.transport;

/** The native adapter consumes this policy before any radio connect/group stream. */
public final class AutomaticDirectTargetTests {
    public static void main(String[] args) throws Exception {
        Class<?> type;
        try { type = Class.forName("dev.ghost.nearbyim.transport.WifiDirectPolicy$FileAttempt"); }
        catch (ClassNotFoundException missing) { throw new AssertionError("Direct must be armed by an approved file attempt", missing); }
        Object attempt = type.getDeclaredConstructor().newInstance();
        var arm = type.getDeclaredMethod("arm", String.class, boolean.class);
        var allows = type.getDeclaredMethod("allows", Peer.class);
        var dial = type.getDeclaredMethod("dial", Peer.class);
        var stop = type.getDeclaredMethod("stop");
        java.lang.reflect.Method armed;
        try { armed=type.getDeclaredMethod("armed"); }
        catch(NoSuchMethodException missing){throw new AssertionError("A fresh authenticated file retry must rearm after native retirement",missing);}
        String target = "63344c17-0b20-4050-8434-edee90357785";
        Peer wanted = Peer.wifiDirect(target, "Pad", "02:11:22:33:44:55");
        Peer other = Peer.wifiDirect("73344c17-0b20-4050-8434-edee90357785", "Pad", "02:11:22:33:44:56");
        check(!(boolean)allows.invoke(attempt,wanted), "Advertisement cannot open an unarmed file stream");
        arm.invoke(attempt,target,true);
        check(!(boolean)allows.invoke(attempt,other), "A same-name different UUID cannot open a file stream");
        check((boolean)allows.invoke(attempt,wanted), "Expected service hint is eligible for subsequent Noise proof");
        check((boolean)dial.invoke(attempt,wanted), "Designated lead automatically dials expected service");
        check(!(boolean)dial.invoke(attempt,wanted), "Repeated discovery must not duplicate a native connect");
        stop.invoke(attempt);
        check(!(boolean)armed.invoke(attempt),"Cancellation clears file rearm authority");
        check(!(boolean)allows.invoke(attempt,wanted), "Late discovery cannot resurrect a canceled attempt");
        arm.invoke(attempt,target,false);
        check((boolean)armed.invoke(attempt),"Only the newly authenticated retry permits retirement rearm");
        check((boolean)allows.invoke(attempt,wanted), "An authenticated retry rearms responder group streams");
        check(!(boolean)dial.invoke(attempt,wanted), "Responder never competes with designated lead");
        System.out.println("Automatic Direct target checks passed");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
