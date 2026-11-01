package android.os;

public final class SystemClock {
    private static long now = 100000;
    public static long elapsedRealtime() { return now; }
    public static void advanceMillis(long duration) { now += duration; }
}
