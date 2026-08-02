package com.drones.vision.adapter.publishhls;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit tests for {@link CadenceEstimator}: PTS quantization/monotonic-bump machinery, and
 * the pre-start cadence measurement / post-start drift detection added to fix a real
 * 2x-slow-motion bug (a fixed 15fps assumption used to be handed to a 30fps source's timestamps
 * and encoder config alike — see {@link CadenceEstimator}'s javadoc and this module's MODULE.md).
 *
 * <p>These bypass {@link MediamtxStreamPublisher} entirely — no recorder, no network — driving
 * everything off synthetic {@link Instant} sequences, so they run fast and deterministically.
 * (Moved out of {@code StreamStateTest} when {@code StreamState}'s cadence-measurement/PTS/drift
 * logic was split into this class, docs/LAYERING-REFACTOR-PLAN.md §5.1.)
 */
class CadenceEstimatorTest {

    // -- nextTimestampMicros: quantization scales to any measured fps, not just 15 --

    @Test
    void thirtyFpsCadenceAdvancesTimestampsAtWallClockRateNotHalfSpeed() {
        CadenceEstimator cadence = new CadenceEstimator();
        double fps = 30.0;
        long periodMicros = Math.round(1_000_000.0 / fps);
        Instant base = Instant.now();
        int frameCount = 30;

        long first = -1;
        long last = -1;
        for (int i = 0; i < frameCount; i++) {
            Instant capturedAt = base.plusNanos(i * periodMicros * 1000L);
            long ts = cadence.nextTimestampMicros(capturedAt, fps);
            if (i == 0) {
                first = ts;
            }
            last = ts;
        }

        long spanMicros = last - first;
        long expectedSpanMicros = (long) (frameCount - 1) * periodMicros;
        // Wall-clock rate at the *measured* 30fps, not the old fixed-15fps grid
        // (which would have produced roughly double this span).
        assertTrue(Math.abs(spanMicros - expectedSpanMicros) <= 2000,
                "expected span ~" + expectedSpanMicros + "us for 30fps cadence, got " + spanMicros + "us");
    }

    @Test
    void fifteenFpsCadenceStillExact() {
        CadenceEstimator cadence = new CadenceEstimator();
        double fps = 15.0;
        long periodMicros = Math.round(1_000_000.0 / fps);
        Instant base = Instant.now();

        // Frames land exactly one measured-grid period apart, so no collision
        // ever forces a quantized recompute -- the returned timestamp must be
        // the exact wall-clock elapsed time, unchanged from pre-fix behavior
        // at this same (previously hardcoded) 15fps rate.
        for (int i = 0; i < 10; i++) {
            Instant capturedAt = base.plusNanos(i * periodMicros * 1000L);
            long ts = cadence.nextTimestampMicros(capturedAt, fps);
            assertEquals(i * periodMicros, ts, "frame " + i);
        }
    }

    @Test
    void monotonicBumpPreservedForBurstyDelivery() {
        CadenceEstimator cadence = new CadenceEstimator();
        double fps = 15.0;
        Instant base = Instant.now();

        long firstTs = cadence.nextTimestampMicros(base, fps);
        // Second frame lands well under one frame period later (4ms << ~66.7ms) --
        // exactly the bursty-delivery scenario documented on nextTimestampMicros.
        long secondTs = cadence.nextTimestampMicros(base.plusMillis(4), fps);

        assertTrue(secondTs > firstTs, "PTS must strictly increase even for a sub-frame-period gap");
        long firstFrameNumber = Math.round(firstTs * fps / 1_000_000.0);
        long secondFrameNumber = Math.round(secondTs * fps / 1_000_000.0);
        assertTrue(secondFrameNumber > firstFrameNumber, "frame numbers must never collide");
    }

    // -- pre-start cadence measurement --

    @Test
    void measuresThirtyFpsSourceCadence() {
        CadenceEstimator cadence = new CadenceEstimator();

        boolean complete = feedMeasurementSamples(cadence, Duration.ofNanos(1_000_000_000L / 30));

        assertTrue(complete);
        assertEquals(30.0, cadence.measuredFrameRateFps(), 0.5);
    }

    @Test
    void measurementNotCompleteBeforeAllSamplesArrive() {
        CadenceEstimator cadence = new CadenceEstimator();
        Instant base = Instant.now();
        Duration gap = Duration.ofMillis(33);

        for (int i = 0; i < CadenceEstimator.CADENCE_MEASUREMENT_FRAMES - 1; i++) {
            boolean complete = cadence.recordMeasurementSample(base.plus(gap.multipliedBy(i)));
            assertFalse(complete,
                    "measurement must not complete before " + CadenceEstimator.CADENCE_MEASUREMENT_FRAMES + " frames arrive");
        }
    }

    @Test
    void measuredFrameRateClampsToConfiguredHighBound() {
        CadenceEstimator cadence = new CadenceEstimator();

        // ~1ms gaps => ~1000fps raw, must clamp down to MAX_MEASURED_FRAME_RATE_FPS.
        feedMeasurementSamples(cadence, Duration.ofMillis(1));

        assertEquals(CadenceEstimator.MAX_MEASURED_FRAME_RATE_FPS, cadence.measuredFrameRateFps());
    }

    @Test
    void measuredFrameRateClampsToConfiguredLowBound() {
        CadenceEstimator cadence = new CadenceEstimator();

        // 10s gaps => 0.1fps raw, must clamp up to MIN_MEASURED_FRAME_RATE_FPS.
        feedMeasurementSamples(cadence, Duration.ofSeconds(10));

        assertEquals(CadenceEstimator.MIN_MEASURED_FRAME_RATE_FPS, cadence.measuredFrameRateFps());
    }

    @Test
    void degenerateIdenticalTimestampsFallBackToDefaultFrameRate() {
        CadenceEstimator cadence = new CadenceEstimator();
        Instant same = Instant.now();

        boolean complete = false;
        for (int i = 0; i < CadenceEstimator.CADENCE_MEASUREMENT_FRAMES; i++) {
            complete = cadence.recordMeasurementSample(same);
        }

        assertTrue(complete);
        assertEquals(CadenceEstimator.DEFAULT_FRAME_RATE_FPS, cadence.measuredFrameRateFps());
    }

    @Test
    void measurementIsNotRepeatedOnceComplete() {
        CadenceEstimator cadence = new CadenceEstimator();
        feedMeasurementSamples(cadence, Duration.ofNanos(1_000_000_000L / 24));
        double measured = cadence.measuredFrameRateFps();

        // Simulate a reconnect: a frame arrives long after the last measurement
        // sample (e.g. after an outage/backoff cycle). The already-completed
        // measurement must be reused verbatim, not recomputed from a stale delta.
        boolean complete = cadence.recordMeasurementSample(Instant.now().plusSeconds(30));

        assertTrue(complete);
        assertEquals(measured, cadence.measuredFrameRateFps());
    }

    // -- post-start drift detection --

    @Test
    void noDriftReportedWhenActualCadenceMatchesMeasuredRate() {
        CadenceEstimator cadence = new CadenceEstimator();
        feedMeasurementSamples(cadence, Duration.ofNanos(1_000_000_000L / 30));
        Instant cursor = Instant.now();

        for (int i = 0; i < 200; i++) {
            cursor = cursor.plusNanos(1_000_000_000L / 30);
            assertFalse(cadence.observeSustainedDrift(cursor));
        }
    }

    @Test
    void transientBlipDoesNotTriggerDrift() {
        CadenceEstimator cadence = new CadenceEstimator();
        feedMeasurementSamples(cadence, Duration.ofNanos(1_000_000_000L / 15));
        Instant cursor = Instant.now();

        for (int i = 0; i < 10; i++) {
            cursor = cursor.plusNanos(1_000_000_000L / 15);
            assertFalse(cadence.observeSustainedDrift(cursor));
        }
        // A brief burst of much-faster arrivals, well under the sustained-drift
        // window, then back to nominal -- must never be reported as drift.
        for (int i = 0; i < 5; i++) {
            cursor = cursor.plusNanos(1_000_000_000L / 60);
            assertFalse(cadence.observeSustainedDrift(cursor));
        }
        for (int i = 0; i < 10; i++) {
            cursor = cursor.plusNanos(1_000_000_000L / 15);
            assertFalse(cadence.observeSustainedDrift(cursor));
        }
    }

    @Test
    void sustainedRateDoubleTriggersDriftExactlyOnce() {
        CadenceEstimator cadence = new CadenceEstimator();
        feedMeasurementSamples(cadence, Duration.ofNanos(1_000_000_000L / 15));
        Instant cursor = Instant.now();

        int triggeredCount = 0;
        // Source cadence doubles to 30fps and stays there well past the
        // sustained-drift window (2s); the 15fps measured rate never catches up
        // (encoder restart mid-stream is out of scope -- known limitation).
        for (int i = 0; i < 200; i++) {
            cursor = cursor.plusNanos(1_000_000_000L / 30);
            if (cadence.observeSustainedDrift(cursor)) {
                triggeredCount++;
            }
        }

        assertEquals(1, triggeredCount, "sustained drift must be reported exactly once per stream");
    }

    private static boolean feedMeasurementSamples(CadenceEstimator cadence, Duration gap) {
        Instant cursor = Instant.now();
        boolean complete = false;
        for (int i = 0; i < CadenceEstimator.CADENCE_MEASUREMENT_FRAMES; i++) {
            complete = cadence.recordMeasurementSample(cursor);
            cursor = cursor.plus(gap);
        }
        return complete;
    }
}
