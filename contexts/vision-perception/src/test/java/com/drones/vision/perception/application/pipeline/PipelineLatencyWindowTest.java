package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.PipelineLatency;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the round-trip/interval window against an explicit nano timeline — no real clock, so
 * every figure below is exact rather than approximately right.
 */
class PipelineLatencyWindowTest {

    private static final long MS = 1_000_000L;

    private static PipelineLatencyWindow window() {
        return new PipelineLatencyWindow(Duration.ofSeconds(30));
    }

    @Test
    void emptyWindowReportsZeroes() {
        PipelineLatency latency = window().snapshot();

        assertEquals(0L, latency.samples());
        assertEquals(0.0, latency.roundTripMillisP50());
        assertEquals(0.0, latency.effectiveFps());
        assertEquals(Duration.ofSeconds(30), latency.window());
    }

    @Test
    void rejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new PipelineLatencyWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PipelineLatencyWindow(Duration.ofSeconds(-1)));
    }

    @Test
    void reportsRoundTripPercentilesAndMax() {
        PipelineLatencyWindow window = window();
        // Ten completions 100 ms apart, round trips 10..100 ms.
        for (int i = 1; i <= 10; i++) {
            long completedAt = i * 100L * MS;
            window.record(completedAt - i * 10L * MS, completedAt);
        }

        PipelineLatency latency = window.snapshot();

        assertEquals(10L, latency.samples());
        assertEquals(50.0, latency.roundTripMillisP50());
        assertEquals(100.0, latency.roundTripMillisP95());
        assertEquals(100.0, latency.roundTripMillisMax());
    }

    @Test
    void updateIntervalAndEffectiveFpsComeFromCompletionSpacing() {
        PipelineLatencyWindow window = window();
        // 10 fps: a completion every 100 ms, each costing 20 ms.
        for (int i = 1; i <= 11; i++) {
            long completedAt = i * 100L * MS;
            window.record(completedAt - 20L * MS, completedAt);
        }

        PipelineLatency latency = window.snapshot();

        assertEquals(100.0, latency.updateIntervalMillisP50());
        assertEquals(10.0, latency.effectiveFps(), 1e-9);
        assertEquals(20.0, latency.roundTripMillisP50());
    }

    @Test
    void worstBoxAgeAddsAFullUpdateIntervalToTheRoundTrip() {
        PipelineLatencyWindow window = window();
        for (int i = 1; i <= 11; i++) {
            long completedAt = i * 100L * MS;
            window.record(completedAt - 30L * MS, completedAt);
        }

        // The point of the two-number split: 30 ms of round trip, but the box is on screen for the
        // full 100 ms sample interval, so what the operator sees is 130 ms old at worst.
        assertEquals(130.0, window.snapshot().worstBoxAgeMillis(), 1e-9);
    }

    @Test
    void evictsSamplesOlderThanTheWindow() {
        PipelineLatencyWindow window = new PipelineLatencyWindow(Duration.ofSeconds(1));
        window.record(0L, 10L * MS);          // ages out
        window.record(500L * MS, 520L * MS);  // ages out (<= now - 1s)
        window.record(1_500L * MS, 1_520L * MS);

        PipelineLatency latency = window.snapshot();

        assertEquals(1L, latency.samples());
        assertEquals(20.0, latency.roundTripMillisP50());
    }

    @Test
    void clearEmptiesTheWindow() {
        PipelineLatencyWindow window = window();
        window.record(0L, 20L * MS);
        window.clear();

        assertEquals(0L, window.snapshot().samples());
    }

    @Test
    void clampsANonAdvancingClockRatherThanReportingNegativeLatency() {
        PipelineLatencyWindow window = window();
        window.record(50L * MS, 40L * MS);

        PipelineLatency latency = window.snapshot();

        assertEquals(1L, latency.samples());
        assertTrue(latency.roundTripMillisP50() >= 0.0, "a percentile must never go negative");
        assertEquals(0.0, latency.roundTripMillisP50());
    }

    @Test
    void aSingleSampleHasNoIntervalAndNoRate() {
        PipelineLatencyWindow window = window();
        window.record(0L, 25L * MS);

        PipelineLatency latency = window.snapshot();

        assertEquals(25.0, latency.roundTripMillisP50());
        assertEquals(0.0, latency.updateIntervalMillisP50());
        assertEquals(0.0, latency.effectiveFps());
    }
}
