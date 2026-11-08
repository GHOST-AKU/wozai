package org.nearbymesh.radio;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounds additional Handler work; overflow permanently stops callback admission. */
final class CallbackBudget {
    private final int capacity;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean overflowed = new AtomicBoolean();

    CallbackBudget(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("callback_capacity");
        this.capacity = capacity;
    }

    boolean acquire() {
        while (!overflowed.get()) {
            int count = pending.get();
            if (count >= capacity) return false;
            if (pending.compareAndSet(count, count + 1)) return true;
        }
        return false;
    }

    void release() { pending.decrementAndGet(); }
    boolean overflow() { return overflowed.compareAndSet(false, true); }
    boolean overflowed() { return overflowed.get(); }
}
