package com.drones.vision.app.usage;

import com.drones.vision.app.config.properties.VisionUsageProperties;
import com.drones.vision.warehouse.application.usage.UsageIdleCloseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UsageIdleCloseRunner} (docs/plans/active/OPERATOR-UX-5-PLAN.md finding U1,
 * wave W1) — real background scheduler throughout, driven at a fast tick interval and observed by
 * polling, the same style {@code TrackProjectionRunnerTest} already uses rather than reaching for a
 * fake clock.
 */
class UsageIdleCloseRunnerTest {

    private static final Duration FAST_SWEEP_PERIOD = Duration.ofMillis(20);

    private final List<UsageIdleCloseRunner> runners = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (UsageIdleCloseRunner runner : runners) {
            runner.close();
        }
    }

    private UsageIdleCloseRunner newRunner(UsageIdleCloseService usageIdleCloseService) {
        UsageIdleCloseRunner runner = new UsageIdleCloseRunner(usageIdleCloseService, properties());
        runners.add(runner);
        return runner;
    }

    private static VisionUsageProperties properties() {
        return new VisionUsageProperties(Duration.ofMinutes(10), FAST_SWEEP_PERIOD);
    }

    private static void awaitTrue(BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail(message);
    }

    @Test
    void sweepsOnceImmediatelyOnStart() throws Exception {
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenReturn(0);

        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);
        runner.start();

        awaitTrue(() -> {
                    try {
                        verify(usageIdleCloseService, atLeast(1)).closeIdleUsages();
                        return true;
                    } catch (AssertionError e) {
                        return false;
                    }
                },
                "the first sweep must fire immediately on start, not after one sweep-period");
    }

    @Test
    void sweepsRepeatedlyOnTheConfiguredCadence() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenAnswer(invocation -> callCount.incrementAndGet());

        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);
        runner.start();

        awaitTrue(() -> callCount.get() >= 3,
                "the sweep must repeat on a fixed cadence -- at least 3 calls expected within the await window");
    }

    @Test
    void aSweepFailureDoesNotStopSubsequentSweeps() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenAnswer(invocation -> {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                throw new RuntimeException("boom");
            }
            return 0;
        });

        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);
        runner.start();

        awaitTrue(() -> callCount.get() >= 2,
                "a RuntimeException from one sweep must not prevent the next scheduled sweep from running");
    }

    @Test
    void startIsIdempotent() {
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenReturn(0);
        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);

        runner.start();
        runner.start(); // must not double-arm the scheduler or throw
    }

    @Test
    void closeIsIdempotent() {
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenReturn(0);
        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);

        runner.start();
        runner.close();
        runner.close(); // idempotent -- must not throw
    }

    @Test
    void closeStopsFurtherSweeps() throws Exception {
        AtomicInteger callCount = new AtomicInteger();
        UsageIdleCloseService usageIdleCloseService = mock(UsageIdleCloseService.class);
        when(usageIdleCloseService.closeIdleUsages()).thenAnswer(invocation -> callCount.incrementAndGet());
        UsageIdleCloseRunner runner = newRunner(usageIdleCloseService);
        runner.start();
        awaitTrue(() -> callCount.get() >= 1, "at least one sweep must run before close");

        runner.close();
        int countAtClose = callCount.get();
        Thread.sleep(100);

        assertTrue(callCount.get() <= countAtClose + 1,
                "no further sweep should start once close() has returned (at most one in-flight sweep may finish)");
    }
}
