package com.drones.vision.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The pull-mode redefinition (docs/plans/active/MEDIA-SOT-PLAN.md &sect;7, wave M5): {@code roundTripMillis}
 * becomes {@code receivedAt - capturedAt} instead of {@code completed - submitted}, fed via {@link
 * PipelineLatencyWindow#recordPullRoundTrip}. {@link PipelineLatencyWindowTest} (push mode, via {@link
 * PipelineLatencyWindow#record}) stays untouched — both share the same underlying window/percentile
 * machinery, so this file only exercises the new entry point.
 */
class PipelineLatencyWindowPullModeTest {

    private static final long MS = 1_000_000L;

    @Test
    void recordPullRoundTripFeedsTheSameWindowAsRecord() {
        PipelineLatencyWindow window = new PipelineLatencyWindow(Duration.ofSeconds(30));

        window.recordPullRoundTrip(100L * MS, 208L * MS);

        PipelineLatency latency = window.snapshot();
        assertEquals(1L, latency.samples());
        assertEquals(208.0, latency.roundTripMillisP50(), 1e-6);
    }

    @Test
    void aNegativeRoundTripIsClampedToZero() {
        // a worker clock ahead of the camera's own capture_skew estimate could momentarily produce
        // a "receivedAt before capturedAt" reading; a degenerate sample must not make a percentile
        // negative, exactly like record()'s own clamp.
        PipelineLatencyWindow window = new PipelineLatencyWindow(Duration.ofSeconds(30));

        window.recordPullRoundTrip(100L * MS, -5L * MS);

        assertEquals(0.0, window.snapshot().roundTripMillisP50());
    }

    @Test
    void multiplePullRoundTripsComputePercentilesAndEffectiveFpsLikePushMode() {
        PipelineLatencyWindow window = new PipelineLatencyWindow(Duration.ofSeconds(30));
        for (int i = 1; i <= 10; i++) {
            long completedAt = i * 100L * MS;
            window.recordPullRoundTrip(completedAt, i * 10L * MS);
        }

        PipelineLatency latency = window.snapshot();
        assertEquals(10L, latency.samples());
        assertEquals(50.0, latency.roundTripMillisP50(), 1e-6);
        assertEquals(100.0, latency.roundTripMillisMax(), 1e-6);
        assertEquals(1_000_000_000.0 / (100L * MS), latency.effectiveFps(), 1e-6);
    }
}
