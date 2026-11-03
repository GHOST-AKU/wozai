package org.nearbymesh.radio;

import java.util.concurrent.*;

/** Process-wide bounds also cover a stopped adapter whose vendor operation never returns. */
final class CocRuntime {
    static ThreadPoolExecutor pool(int size, String name) {
        return new ThreadPoolExecutor(0, size, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), action -> {
            Thread thread = new Thread(action, name); thread.setDaemon(true); return thread;
        });
    }
    static final ThreadPoolExecutor IO = pool(4, "mesh-coc-io");
    static final ThreadPoolExecutor BIND = pool(1, "mesh-coc-bind");
    static final ThreadPoolExecutor ACCEPT = pool(1, "mesh-coc-accept");
    static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "mesh-coc-deadline"); thread.setDaemon(true); return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    interface Ticket { void cancel(); }
    interface Time {
        long now();
        Ticket after(long delay, Runnable action);
    }
    static final Time TIME = new Time() {
        public long now() { return System.nanoTime() / 1000000L; }
        public Ticket after(long delay, Runnable action) {
            ScheduledFuture<?> future = TIMER.schedule(action, Math.max(0, delay), TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }
    };
    private CocRuntime() { }
}
