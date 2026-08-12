package com.drones.vision.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for the sampler's accounting (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R1) — the
 * counters that turn "this stream is not running at its configured rate" into a diagnosis.
 */
class DetectionRateWindowTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final long SECOND = 1_000_000_000L;

    @Test
    void reportsZeroesBeforeAnyDeadlineIsServed() {
        DetectionRate rate = new DetectionRateWindow(WINDOW).snapshot(30.0, 10.0);

        assertEquals(0L, rate.due());
        assertEquals(0.0, rate.submittedFps());
        assertEquals(0.0, rate.dropRatio());
        assertEquals(30.0, rate.sourceFps(), "the two known figures survive an empty window");
        assertEquals(10.0, rate.targetFps());
    }

    @Test
    void countsEachOutcomeUnderItsOwnHeading() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW);
        window.record(DetectionRateWindow.Outcome.SUBMITTED, 0L);
        window.record(DetectionRateWindow.Outcome.DROPPED_IN_FLIGHT, SECOND / 10);
        window.record(DetectionRateWindow.Outcome.DROPPED_OUTAGE, SECOND / 5);
        window.record(DetectionRateWindow.Outcome.SUBMITTED, SECOND / 3);

        DetectionRate rate = window.snapshot(30.0, 10.0);

        assertEquals(2L, rate.submitted());
        assertEquals(1L, rate.droppedInFlight());
        assertEquals(1L, rate.droppedOutage());
        assertEquals(4L, rate.due());
        assertEquals(0.5, rate.dropRatio(), 1e-9);
    }

    @Test
    void measuresSubmittedFpsAcrossTheSamplesOwnSpanRatherThanTheWholeWindow() {
        // Same contract as PipelineLatencyWindow's: a stream one second into a ten-second window
        // must report the rate it is actually running at, not one diluted by nine seconds it was
        // not running for.
        DetectionRateWindow window = new DetectionRateWindow(WINDOW);
        for (int i = 0; i <= 10; i++) {
            window.record(DetectionRateWindow.Outcome.SUBMITTED, i * SECOND / 10);
        }

        assertEquals(11.0, window.snapshot(30.0, 10.0).submittedFps(), 1e-6);
    }

    @Test
    void evictsSamplesOlderThanTheWindowButKeepsTheStarvationTotal() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW);
        window.record(DetectionRateWindow.Outcome.SUBMITTED, 0L);
        window.recordMissedDeadlines(4L);
        window.record(DetectionRateWindow.Outcome.SUBMITTED, 20 * SECOND); // pushes the first out

        DetectionRate rate = window.snapshot(30.0, 10.0);

        assertEquals(1L, rate.submitted(), "the sample older than the window is gone");
        assertEquals(4L, rate.missedDeadlines(),
                "starvation is a running total: a starving source produces no events to age out");
    }

    @Test
    void clearForgetsEverythingIncludingTheStarvationTotal() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW);
        window.record(DetectionRateWindow.Outcome.SUBMITTED, 0L);
        window.recordMissedDeadlines(3L);

        window.clear();

        DetectionRate rate = window.snapshot(30.0, 10.0);
        assertEquals(0L, rate.due());
        assertEquals(0L, rate.missedDeadlines());
    }

    @Test
    void ignoresANonPositiveMissedDeadlineCount() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW);

        window.recordMissedDeadlines(0L);
        window.recordMissedDeadlines(-5L);

        assertEquals(0L, window.snapshot(30.0, 10.0).missedDeadlines());
    }

    @Test
    void rejectsANonPositiveWindow() {
        assertThrows(IllegalArgumentException.class, () -> new DetectionRateWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new DetectionRateWindow(Duration.ofSeconds(-1)));
    }
}
