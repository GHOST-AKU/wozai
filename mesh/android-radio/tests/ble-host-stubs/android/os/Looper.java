package android.os;

public final class Looper {
    private static final Thread OWNER = Thread.currentThread();
    private static final Looper INSTANCE = new Looper();
    public static Looper myLooper() { return Thread.currentThread() == OWNER ? INSTANCE : null; }
    public static Looper getMainLooper() { return INSTANCE; }
}
