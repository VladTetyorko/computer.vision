package com.drones.vision.adapter.publishhls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link PublishDiagnostics}: docs/MVP2-PLAN.md V-c's capture→encode lag
 * summary log gating, and that its {@link LagTracker} is a plain per-stream accumulator. (Moved
 * out of {@code StreamStateTest} when {@code StreamState}'s lag-diagnostics bookkeeping was split
 * into this class, docs/LAYERING-REFACTOR-PLAN.md §5.1.)
 */
class PublishDiagnosticsTest {

    @Test
    void firstShouldLogLagCallEstablishesBaselineWithoutLogging() {
        PublishDiagnostics diagnostics = new PublishDiagnostics();

        assertFalse(diagnostics.shouldLogLag(1_000L), "the very first call must only establish the baseline");
    }

    @Test
    void shouldLogLagStaysFalseUntilTheIntervalElapses() {
        PublishDiagnostics diagnostics = new PublishDiagnostics();
        long start = 1_000L;
        diagnostics.shouldLogLag(start);

        assertFalse(diagnostics.shouldLogLag(start + 1),
                "must not log again immediately after the baseline call");
        assertFalse(diagnostics.shouldLogLag(start + PublishDiagnostics.LAG_LOG_INTERVAL_MILLIS - 1),
                "must not log 1ms before the interval elapses");
    }

    @Test
    void shouldLogLagFiresOnceIntervalElapsesThenResetsForTheNextWindow() {
        PublishDiagnostics diagnostics = new PublishDiagnostics();
        long start = 1_000L;
        diagnostics.shouldLogLag(start);

        long dueAt = start + PublishDiagnostics.LAG_LOG_INTERVAL_MILLIS;
        assertTrue(diagnostics.shouldLogLag(dueAt), "must fire exactly at the interval boundary");
        assertFalse(diagnostics.shouldLogLag(dueAt + 1), "must not fire again immediately after logging");
        assertTrue(diagnostics.shouldLogLag(dueAt + PublishDiagnostics.LAG_LOG_INTERVAL_MILLIS),
                "must fire again once a full interval has elapsed since the last log");
    }

    @Test
    void lagTrackerAccumulatesSamplesRecordedOnTheDiagnostics() {
        PublishDiagnostics diagnostics = new PublishDiagnostics();

        diagnostics.lagTracker.record(10L);
        diagnostics.lagTracker.record(20L);
        diagnostics.lagTracker.record(30L);

        assertEquals(3, diagnostics.lagTracker.sampleCount());
        assertEquals(20L, diagnostics.lagTracker.p50());
    }
}
