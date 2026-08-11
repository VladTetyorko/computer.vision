package com.drones.vision.adapter.publishhls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Direct unit tests for {@link LagTracker}'s pure ring-buffer/percentile
 * math (docs/plans/done/MVP2-PLAN.md V-c) — no {@link MediamtxStreamPublisher}, no
 * clock, no I/O.
 */
class LagTrackerTest {

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new LagTracker(0));
        assertThrows(IllegalArgumentException.class, () -> new LagTracker(-1));
    }

    @Test
    void emptyTrackerReportsZeroPercentilesAndZeroCount() {
        LagTracker tracker = new LagTracker(4);

        assertEquals(0, tracker.sampleCount());
        assertEquals(0L, tracker.p50());
        assertEquals(0L, tracker.p95());
    }

    @Test
    void singleSampleIsReturnedForBothPercentiles() {
        LagTracker tracker = new LagTracker(5);

        tracker.record(42L);

        assertEquals(1, tracker.sampleCount());
        assertEquals(42L, tracker.p50());
        assertEquals(42L, tracker.p95());
    }

    /**
     * Capacity=4, samples [10, 20, 30, 40] (no wrap yet): sorted order is
     * unchanged since they arrive already sorted, and nearest-rank
     * percentiles land on the 2nd and 4th of 4 samples respectively —
     * hand-computed via {@code ceil(p * n) - 1}.
     */
    @Test
    void percentilesWithinCapacityMatchNearestRankFormula() {
        LagTracker tracker = new LagTracker(4);

        tracker.record(10L);
        tracker.record(20L);
        tracker.record(30L);
        tracker.record(40L);

        assertEquals(4, tracker.sampleCount());
        assertEquals(20L, tracker.p50(), "ceil(0.50*4)-1 = 1 -> sorted[1]");
        assertEquals(40L, tracker.p95(), "ceil(0.95*4)-1 = 3 -> sorted[3]");
    }

    /**
     * Regression test for the "drop-oldest, not drop-newest" contract: once
     * capacity is exceeded, the two oldest samples (10, 20) must be
     * overwritten by the two newest (50, 60), leaving the window at
     * {30, 40, 50, 60} — never silently ignoring new samples in favor of
     * stale ones (which would defeat the point of a *recent* lag summary).
     */
    @Test
    void windowWrapsAndDropsTheOldestSamplesNotTheNewest() {
        LagTracker tracker = new LagTracker(4);

        tracker.record(10L);
        tracker.record(20L);
        tracker.record(30L);
        tracker.record(40L);
        tracker.record(50L); // overwrites 10
        tracker.record(60L); // overwrites 20

        assertEquals(4, tracker.sampleCount(), "count must cap at capacity, never grow past it");
        assertEquals(40L, tracker.p50(), "window is now {30,40,50,60}; ceil(0.50*4)-1=1 -> sorted[1]");
        assertEquals(60L, tracker.p95(), "window is now {30,40,50,60}; ceil(0.95*4)-1=3 -> sorted[3]");
    }

    /**
     * Full-window, no-wrap sanity check at a scale closer to real usage
     * (docs/plans/done/MVP2-PLAN.md V-c's own {@code LAG_TRACKER_WINDOW_SIZE}=150 is
     * this shape, just smaller here for a readable hand computation):
     * samples 1..100 recorded in order, so sorted[i] == i+1 exactly.
     */
    @Test
    void percentilesAtFullWindowMatchExpectedRanks() {
        LagTracker tracker = new LagTracker(100);

        for (long value = 1; value <= 100; value++) {
            tracker.record(value);
        }

        assertEquals(100, tracker.sampleCount());
        assertEquals(50L, tracker.p50(), "ceil(0.50*100)-1 = 49 -> sorted[49] = 50");
        assertEquals(95L, tracker.p95(), "ceil(0.95*100)-1 = 94 -> sorted[94] = 95");
    }
}
