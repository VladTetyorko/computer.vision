package com.drones.mavlink.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DefaultTxScheduler} tests use fast, injected periods (tens of ms) and assert a fired
 * <i>count</i> over a bounded polling window, never a wall-clock Hz -- exactly the acceptance
 * gate's own instruction, since asserting real-time cadence is inherently flaky under load.
 */
class DefaultTxSchedulerTest {

    @Test
    void firesRoughlyAtTheRequestedPeriod() throws Exception {
        DefaultTxScheduler scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        try {
            AtomicInteger count = new AtomicInteger();
            TxScheduler.Handle handle = scheduler.repeat("test-task", Duration.ofMillis(20), count::incrementAndGet);

            awaitAtLeast(count, 5, Duration.ofSeconds(2));

            handle.close();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void handleCloseIsIdempotentAndStopsFurtherFiring() throws Exception {
        DefaultTxScheduler scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        try {
            AtomicInteger count = new AtomicInteger();
            TxScheduler.Handle handle = scheduler.repeat("test-task", Duration.ofMillis(15), count::incrementAndGet);

            awaitAtLeast(count, 2, Duration.ofSeconds(2));
            handle.close();
            handle.close(); // must not throw

            int countAfterClose = count.get();
            Thread.sleep(100); // give any still-in-flight execution a chance to land, then confirm no more
            assertTrue(count.get() <= countAfterClose + 1,
                    "no further executions should fire after close(), got " + count.get() + " vs " + countAfterClose);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void aThrowingTaskDoesNotStopTheSchedule() throws Exception {
        DefaultTxScheduler scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        try {
            AtomicInteger attempts = new AtomicInteger();
            TxScheduler.Handle handle = scheduler.repeat("throwing-task", Duration.ofMillis(15), () -> {
                attempts.incrementAndGet();
                throw new RuntimeException("boom -- a deliberately broken periodic task");
            });

            awaitAtLeast(attempts, 5, Duration.ofSeconds(2));

            handle.close();
        } finally {
            scheduler.close();
        }
    }

    @Test
    void twoTasksShareTheSchedulerIndependently() throws Exception {
        DefaultTxScheduler scheduler = new DefaultTxScheduler(Duration.ofSeconds(2));
        try {
            AtomicInteger a = new AtomicInteger();
            AtomicInteger b = new AtomicInteger();
            TxScheduler.Handle handleA = scheduler.repeat("task-a", Duration.ofMillis(15), a::incrementAndGet);
            TxScheduler.Handle handleB = scheduler.repeat("task-b", Duration.ofMillis(15), b::incrementAndGet);

            awaitAtLeast(a, 4, Duration.ofSeconds(2));
            awaitAtLeast(b, 4, Duration.ofSeconds(2));

            handleA.close();
            handleB.close();
        } finally {
            scheduler.close();
        }
    }

    private static void awaitAtLeast(AtomicInteger counter, int target, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (counter.get() >= target) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(counter.get() >= target,
                "expected at least " + target + " firings within " + timeout + ", got " + counter.get());
    }
}
