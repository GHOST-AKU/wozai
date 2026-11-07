package org.nearbymesh.radio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Real concurrent host-JVM checks for the extra native-callback queue bound. */
public final class CallbackBudgetTest {
    public static void main(String[] args) throws Exception {
        capacityReturnsOnlyAfterCompletion();
        overflowIsTerminalAndCoalesced();
        concurrentProducersCannotExceedCapacity();
        invalidCapacityIsRejected();
        System.out.println("CallbackBudgetTest: 4 passed");
    }

    private static void capacityReturnsOnlyAfterCompletion() {
        CallbackBudget budget = new CallbackBudget(2);
        check(budget.acquire() && budget.acquire(), "must accept two pending callbacks");
        check(!budget.acquire(), "must reject a third pending callback");
        budget.release();
        check(budget.acquire(), "completion must return exactly one slot");
        check(!budget.acquire(), "one completion must not return two slots");
        budget.release(); budget.release();
    }

    private static void overflowIsTerminalAndCoalesced() {
        CallbackBudget budget = new CallbackBudget(2);
        check(budget.acquire(), "first callback must fit");
        check(budget.overflow(), "first overflow must schedule closure");
        check(!budget.overflow(), "subsequent overflow must not schedule more closures");
        budget.release();
        check(budget.overflowed() && !budget.acquire(), "overflow must remain closed after pending work drains");
    }

    private static void concurrentProducersCannotExceedCapacity() throws Exception {
        CallbackBudget budget = new CallbackBudget(4);
        CountDownLatch start = new CountDownLatch(1), attempted = new CountDownLatch(32), finish = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    boolean reserved = budget.acquire();
                    if (reserved) accepted.incrementAndGet();
                    attempted.countDown();
                    finish.await();
                    if (reserved) budget.release();
                } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            });
            thread.setDaemon(true);
            threads.add(thread); thread.start();
        }
        start.countDown();
        boolean completed = attempted.await(5, TimeUnit.SECONDS);
        int count = accepted.get();
        finish.countDown();
        for (Thread thread : threads) thread.join(5000);
        check(completed, "all concurrent callback attempts must complete");
        check(count == 4, "concurrent producers must reserve exactly four available slots");
        for (int i = 0; i < 4; i++) check(budget.acquire(), "all completed slots must be reusable");
        check(!budget.acquire(), "concurrent release must preserve the capacity bound");
    }

    private static void invalidCapacityIsRejected() {
        try { new CallbackBudget(0); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("zero callback capacity must be rejected");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
