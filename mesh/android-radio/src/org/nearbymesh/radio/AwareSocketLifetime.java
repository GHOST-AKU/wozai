package org.nearbymesh.radio;

/** Coordinates cancellation and descriptor ownership without allocating per I/O operation. */
final class AwareSocketLifetime {
    private final Runnable shutdown, release;
    private boolean closed, released;
    private int operations;

    AwareSocketLifetime(Runnable shutdown, Runnable release) {
        this.shutdown = shutdown;
        this.release = release;
    }

    synchronized boolean begin() {
        if (closed) return false;
        ++operations;
        return true;
    }

    synchronized void end() {
        if (operations <= 0) throw new IllegalStateException("socket_operation_not_started");
        --operations;
        releaseIfIdle();
    }

    synchronized void close() {
        if (closed) return;
        closed = true;
        // Keep shutdown and the final release ordered even when the last I/O ends concurrently.
        try { shutdown.run(); }
        finally { releaseIfIdle(); }
    }

    private void releaseIfIdle() {
        if (closed && operations == 0 && !released) {
            released = true;
            release.run();
        }
    }
}
