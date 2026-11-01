package org.nearbymesh.radio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Descriptor ownership checks; native syscall behavior still requires Android testing. */
public final class AwareSocketLifetimeTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        activeOperationRetainsDescriptor();
        closeWithoutOperationsReleasesOnce();
        shutdownAndLastOperationCannotRaceRelease();
        shutdownFailureStillReleasesDescriptor();
        System.out.println("AwareSocketLifetimeTest: " + checks + " checks passed");
    }

    private static void activeOperationRetainsDescriptor() {
        AtomicInteger shutdowns = new AtomicInteger(), releases = new AtomicInteger();
        AwareSocketLifetime lifetime = new AwareSocketLifetime(
                shutdowns::incrementAndGet, releases::incrementAndGet);
        check(lifetime.begin(), "read operation rejected before close");
        check(lifetime.begin(), "write operation rejected before close");
        lifetime.close();
        check(shutdowns.get() == 1, "cancellation did not interrupt active I/O");
        check(releases.get() == 0, "descriptor released while native I/O can still use it");
        check(!lifetime.begin(), "new native operation accepted after close");
        lifetime.end();
        check(releases.get() == 0, "descriptor released before final operation completed");
        lifetime.end();
        check(releases.get() == 1, "final operation did not release descriptor");
        lifetime.close();
        check(shutdowns.get() == 1 && releases.get() == 1, "descriptor closed twice");
    }

    private static void closeWithoutOperationsReleasesOnce() {
        AtomicInteger releases = new AtomicInteger();
        AwareSocketLifetime lifetime = new AwareSocketLifetime(() -> { }, releases::incrementAndGet);
        check(lifetime.begin(), "setup operation rejected");
        lifetime.end();
        check(releases.get() == 0, "open descriptor released after setup");
        lifetime.close();
        lifetime.close();
        check(releases.get() == 1, "idle descriptor was not released exactly once");
    }

    private static void shutdownAndLastOperationCannotRaceRelease() throws Exception {
        CountDownLatch shutdownEntered = new CountDownLatch(1), finishShutdown = new CountDownLatch(1);
        CountDownLatch operationEnded = new CountDownLatch(1);
        AtomicInteger releases = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AwareSocketLifetime lifetime = new AwareSocketLifetime(() -> {
            shutdownEntered.countDown();
            try {
                if (!finishShutdown.await(2, TimeUnit.SECONDS)) throw new AssertionError("shutdown stalled");
                check(releases.get() == 0, "descriptor released during shutdown syscall");
            } catch (InterruptedException error) { throw new AssertionError(error); }
        }, releases::incrementAndGet);
        check(lifetime.begin(), "native operation rejected");
        Thread closer = new Thread(() -> {
            try { lifetime.close(); } catch (Throwable error) { failure.set(error); }
        });
        Thread operation = new Thread(() -> {
            try { lifetime.end(); } catch (Throwable error) { failure.set(error); }
            finally { operationEnded.countDown(); }
        });
        closer.start();
        check(shutdownEntered.await(2, TimeUnit.SECONDS), "shutdown did not start");
        operation.start();
        finishShutdown.countDown();
        closer.join(2_000);
        check(operationEnded.await(2, TimeUnit.SECONDS), "operation completion deadlocked");
        operation.join(2_000);
        if (failure.get() != null) throw new AssertionError(failure.get());
        check(!closer.isAlive() && !operation.isAlive(), "lifecycle threads leaked");
        check(releases.get() == 1, "concurrent cancellation did not release exactly once");
    }

    private static void shutdownFailureStillReleasesDescriptor() {
        for (boolean active : new boolean[] {false, true}) {
            AtomicInteger releases = new AtomicInteger();
            AwareSocketLifetime lifetime = new AwareSocketLifetime(() -> {
                throw new IllegalStateException("shutdown_failed");
            }, releases::incrementAndGet);
            if (active) check(lifetime.begin(), "operation rejected before shutdown failure");
            try {
                lifetime.close();
                throw new AssertionError("shutdown failure hidden");
            } catch (IllegalStateException expected) { }
            check(!lifetime.begin(), "shutdown failure allowed new operations");
            if (active) {
                check(releases.get() == 0, "shutdown failure released an active descriptor");
                lifetime.end();
            }
            lifetime.close();
            check(releases.get() == 1, "shutdown failure leaked or released descriptor twice");
        }
    }

    private static void check(boolean condition, String message) {
        ++checks;
        if (!condition) throw new AssertionError(message);
    }
}
