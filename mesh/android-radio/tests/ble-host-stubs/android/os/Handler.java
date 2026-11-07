package android.os;

import java.util.PriorityQueue;

/** Deterministic due-time queue; deliberately no Android looper or radio simulation. */
public final class Handler {
    private static final class Task implements Comparable<Task> {
        final Runnable action;
        final long due, order;
        Task(Runnable action, long due, long order) { this.action = action; this.due = due; this.order = order; }
        public int compareTo(Task other) {
            int time = Long.compare(due, other.due);
            return time != 0 ? time : Long.compare(order, other.order);
        }
    }
    private final PriorityQueue<Task> tasks = new PriorityQueue<>();
    private long sequence;
    public Looper getLooper() { return Looper.getMainLooper(); }
    public boolean post(Runnable action) { return postDelayed(action, 0); }
    public synchronized boolean postDelayed(Runnable action, long delay) {
        tasks.add(new Task(action, SystemClock.elapsedRealtime() + Math.max(0, delay), sequence++)); return true;
    }
    public synchronized void removeCallbacks(Runnable action) { tasks.removeIf(task -> task.action == action); }
    public void drain() {
        while (true) {
            Task task;
            synchronized (this) {
                if (tasks.isEmpty() || tasks.peek().due > SystemClock.elapsedRealtime()) return;
                task = tasks.remove();
            }
            task.action.run();
        }
    }
    public void advanceMillis(long duration) {
        long target = SystemClock.elapsedRealtime() + duration;
        while (true) {
            long due;
            synchronized (this) { due = tasks.isEmpty() ? Long.MAX_VALUE : tasks.peek().due; }
            if (due > target) break;
            SystemClock.advanceMillis(Math.max(0, due - SystemClock.elapsedRealtime()));
            drain();
        }
        SystemClock.advanceMillis(Math.max(0, target - SystemClock.elapsedRealtime()));
        drain();
    }
}
