package android.os;

import java.util.ArrayDeque;

/** Deterministic queue only: this does not emulate Android scheduling. */
public final class Handler {
    private final ArrayDeque<Runnable> ready = new ArrayDeque<>();
    private final ArrayDeque<Runnable> delayed = new ArrayDeque<>();
    public Looper getLooper() { return Looper.getMainLooper(); }
    public boolean post(Runnable action) { ready.addLast(action); return true; }
    public boolean postDelayed(Runnable action, long delay) { delayed.addLast(action); return true; }
    public void removeCallbacks(Runnable action) { ready.remove(action); delayed.remove(action); }
    public void drain() { while (!ready.isEmpty()) ready.removeFirst().run(); }
}
