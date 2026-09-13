package com.drones.vision.perception.application.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FrameSampler} alone (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave W8.0
 * extraction) — a fake clock, no {@link StreamPipeline}. Every scenario here reproduces a behavior
 * {@code StreamPipeline}'s own (pre-W8) inline fields/methods had, ported unchanged; see that
 * class's git history for the pre-extraction versions these are traced against.
 *
 * <p>{@link DetectionRateController} is always constructed with {@link AdaptiveRateSettings#disabled()}
 * so {@link DetectionRateController#targetFps} is the identity function on its {@code floorFps}
 * argument — isolating {@link FrameSampler}'s own deadline arithmetic from the rate loop's, which
 * {@link DetectionRateControllerTest} already covers alone.
 */
class FrameSamplerTest {

    private static final double HFOV_DEGREES = 60.0;
    private static final int MAX_IN_FLIGHT = 2;

    private static DetectionRateController noopRateController() {
        return new DetectionRateController(AdaptiveRateSettings.disabled(), HFOV_DEGREES);
    }

    private static DetectionRateWindow rateWindow() {
        return new DetectionRateWindow(Duration.ofSeconds(30));
    }

    private static StreamPipelineSettings settingsWith(int warmupFrames, int assumedSourceFps,
                                                         double minMeasuredFps, double maxMeasuredFps) {
        StreamPipelineSettings d = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(assumedSourceFps, d.measuredFpsEwmaAlpha(), warmupFrames,
                minMeasuredFps, maxMeasuredFps, d.detectionBackoffInitialNanos(), d.detectionBackoffMaxNanos(),
                d.sourceReopenBackoffInitialNanos(), d.sourceReopenBackoffMaxNanos(), d.trackingStatsWindow(),
                d.trackRetention(), d.trackingSeed(), d.cameraHfovDegrees(), d.adaptiveRate(),
                d.detectionDemandPollInterval(), d.detectionDemandGrace(), d.videoStaleAfter(), d.renderTier(),
                d.gateLedgerDepth(), d.frameLedgerDepth());
    }

    @Test
    void reportsTheAssumedRateUntilWarmupFramesHaveArrived() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(settingsWith(3, 30, 1.0, 240.0), clock);

        // 20ms apart -- 50fps -- deliberately different from the 30fps assumption, so a test that
        // read the assumption by accident (rather than genuinely never having measured yet) would fail.
        sampler.recordArrival();
        clock.advance(20_000_000L);
        sampler.recordArrival();
        assertEquals(30, sampler.sourceFps(), 1e-9, "only one arrival counted a delta -- warmup not yet reached");

        clock.advance(20_000_000L);
        sampler.recordArrival();
        // framesObserved() is now 3 == warmupFrames: the measurement is trusted from here on.
        assertEquals(50.0, sampler.sourceFps(), 1e-6, "warmup satisfied -- the measured rate now reports");
    }

    @Test
    void clampsTheMeasuredRateToTheConfiguredBounds() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(settingsWith(2, 30, 5.0, 100.0), clock);

        sampler.recordArrival();
        clock.advance(1L); // 1ns apart -- an absurd instantaneous rate, clamped rather than reported raw
        sampler.recordArrival();

        assertEquals(100.0, sampler.sourceFps(), 1e-9, "clamped at the configured ceiling, not ~1e9 fps");
    }

    @Test
    void framesObservedAndNanosSinceLastFrameTrackArrivalsExactly() {
        SettableClock clock = new SettableClock(1_000L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);

        assertEquals(Long.MAX_VALUE, sampler.nanosSinceLastFrame(), "no arrival yet -- infinitely stale");
        assertEquals(0L, sampler.framesObserved());

        sampler.recordArrival();
        clock.advance(500L);

        assertEquals(1L, sampler.framesObserved());
        assertEquals(500L, sampler.nanosSinceLastFrame());
    }

    @Test
    void armsTheScheduleOnTheFirstCallAndSamplesImmediately() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);
        DetectionRateController rateController = noopRateController();
        DetectionRateWindow rate = rateWindow();

        boolean due = sampler.sampleDue(0L, rateController, 10, MAX_IN_FLIGHT, rate);

        assertTrue(due, "the very first deadline is never in the past -- the schedule arms and samples");
    }

    @Test
    void isNotDueStrictlyBeforeTheDeadlineAndIsDueExactlyAtIt() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);
        DetectionRateController rateController = noopRateController();
        DetectionRateWindow rate = rateWindow();
        long intervalNanos = 100_000_000L; // 10 fps

        assertTrue(sampler.sampleDue(0L, rateController, 10, MAX_IN_FLIGHT, rate), "arms at t=0");

        assertFalse(sampler.sampleDue(intervalNanos - 1, rateController, 10, MAX_IN_FLIGHT, rate),
                "one nanosecond before the deadline: not due");
        assertTrue(sampler.sampleDue(intervalNanos, rateController, 10, MAX_IN_FLIGHT, rate),
                "exactly at the deadline: due, and the schedule advances by one more interval");
        assertFalse(sampler.sampleDue(intervalNanos + 1, rateController, 10, MAX_IN_FLIGHT, rate),
                "one nanosecond past the just-served deadline: not due again yet");
    }

    @Test
    void aStalledSourceCatchingUpCountsMissedDeadlinesRatherThanBurstSampling() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);
        DetectionRateController rateController = noopRateController();
        DetectionRateWindow rate = rateWindow();
        long intervalNanos = 100_000_000L; // 10 fps

        assertTrue(sampler.sampleDue(0L, rateController, 10, MAX_IN_FLIGHT, rate), "arms at t=0");

        // The source stalls for 355ms -- more than three whole intervals past the next deadline
        // (100ms). The late clamp must restart the schedule from `now`, not fire a catch-up burst.
        long stalledArrival = 355_000_000L;
        assertTrue(sampler.sampleDue(stalledArrival, rateController, 10, MAX_IN_FLIGHT, rate),
                "the stalled frame itself still serves a deadline");
        assertEquals(2L, rate.snapshot(10.0, 10.0, 0.0).missedDeadlines(),
                "(355ms - 100ms) / 100ms == 2 whole deadlines missed in between");

        // Schedule restarted from `stalledArrival`, not from the old deadline: the very next frame,
        // one interval later, is due -- confirming there is no accumulated debt left to burst through.
        assertFalse(sampler.sampleDue(stalledArrival + intervalNanos - 1, rateController, 10, MAX_IN_FLIGHT, rate));
        assertTrue(sampler.sampleDue(stalledArrival + intervalNanos, rateController, 10, MAX_IN_FLIGHT, rate));
    }

    @Test
    void aFrozenOrBackwardsClockFailsOpenRatherThanStoppingDetectionSilently() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);
        DetectionRateController rateController = noopRateController();
        DetectionRateWindow rate = rateWindow();

        assertTrue(sampler.sampleDue(1_000L, rateController, 10, MAX_IN_FLIGHT, rate), "arms at t=1000");

        // A degenerate clock reading at or before the last served deadline: fail OPEN, not closed.
        assertTrue(sampler.sampleDue(1_000L, rateController, 10, MAX_IN_FLIGHT, rate),
                "clock did not advance at all -- still samples rather than silently stalling detection");
        assertTrue(sampler.sampleDue(500L, rateController, 10, MAX_IN_FLIGHT, rate),
                "clock stepped backwards -- same fail-open rule");
    }

    @Test
    void aRateChangeTakesEffectOnlyAtTheNextArmedDeadlineNotRetroactively() {
        SettableClock clock = new SettableClock(0L);
        FrameSampler sampler = new FrameSampler(StreamPipelineSettings.defaults(), clock);
        DetectionRateController rateController = noopRateController();
        DetectionRateWindow rate = rateWindow();

        // Armed at 10 fps -- next deadline at 100ms.
        assertTrue(sampler.sampleDue(0L, rateController, 10, MAX_IN_FLIGHT, rate));

        // Asking for 100 fps (10ms interval) before the already-armed 100ms deadline arrives changes
        // nothing yet -- the deadline that was already scheduled is honored first.
        assertFalse(sampler.sampleDue(50_000_000L, rateController, 100, MAX_IN_FLIGHT, rate),
                "the 10fps deadline armed earlier still governs -- not yet due at 50ms");

        // At/after the originally-armed 100ms deadline, the NEW target (100fps -> 10ms interval) is
        // the one used to arm the following deadline.
        assertTrue(sampler.sampleDue(100_000_000L, rateController, 100, MAX_IN_FLIGHT, rate));
        assertFalse(sampler.sampleDue(109_000_000L, rateController, 100, MAX_IN_FLIGHT, rate),
                "still inside the newly-armed 10ms interval");
        assertTrue(sampler.sampleDue(110_000_000L, rateController, 100, MAX_IN_FLIGHT, rate),
                "the 10ms interval requested by the new rate has now elapsed");
    }

    /** @see StreamPipelineGateLedgerTest.SettableClock -- duplicated here per this file's own header javadoc. */
    private static final class SettableClock implements LongSupplier {
        private long now;

        SettableClock(long initial) {
            this.now = initial;
        }

        @Override
        public long getAsLong() {
            return now;
        }

        void advance(long nanos) {
            now += nanos;
        }
    }
}
