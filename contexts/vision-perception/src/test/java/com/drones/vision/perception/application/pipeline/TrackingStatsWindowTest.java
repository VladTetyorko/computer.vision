package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionSource;
import com.drones.vision.perception.domain.model.DetectorReason;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TrackRef;
import com.drones.vision.perception.domain.model.TrackState;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.TrackingStats;
import com.drones.vision.perception.domain.model.TrackingTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Like {@link TrackBookTest}, every timestamp comes from the result's own {@code capturedAt} — the
 * window has no clock, so these percentile/ratio assertions are exact.
 */
class TrackingStatsWindowTest {

    private static final ModelRef MODEL = new ModelRef("yolo26n.pt", "latest");
    private static final Instant T0 = Instant.parse("2026-08-11T10:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(30);

    private final StreamId streamId = StreamId.random();

    private DetectionResult frame(long sequence, Instant at, TrackingTelemetry telemetry, Detection... detections) {
        return new DetectionResult(streamId, sequence, at, List.of(detections), Duration.ZERO, telemetry, null, List.of(), Optional.empty());
    }

    /** A tracker-only frame: no detector pass, so no reason, costing {@code trackerMillis}. */
    private static TrackingTelemetry trackerOnly(double trackerMillis, long lockedTrackId) {
        return new TrackingTelemetry(false, null, Duration.ofNanos((long) (trackerMillis * 1_000_000)), "lk",
                lockedTrackId);
    }

    /** A verify frame: the detector ran, for {@code reason}. */
    private static TrackingTelemetry detectorPass(DetectorReason reason, long lockedTrackId) {
        return new TrackingTelemetry(true, reason, Duration.ZERO, "lk", lockedTrackId);
    }

    private static Detection tracked(long trackId, TrackState state) {
        return new Detection("car", 0.8, new BoundingBox(0.3, 0.4, 0.1, 0.1), MODEL,
                new TrackRef(trackId, state, DetectionSource.TRACKER));
    }

    @Test
    void anEmptyWindowReportsTheEmptySnapshotForTheModeItIsAskedAbout() {
        TrackingStats stats = new TrackingStatsWindow(WINDOW).snapshot(TrackingMode.FOLLOW);

        assertEquals(TrackingMode.FOLLOW, stats.mode());
        assertEquals(WINDOW, stats.window());
        assertEquals("", stats.engineId());
        assertEquals(0L, stats.detectorPasses());
        assertEquals(0L, stats.trackerFrames());
        assertEquals(0.0, stats.dutyRatio());
        assertEquals(0.0, stats.trackerMillisP50());
        assertEquals(0.0, stats.trackerMillisP95());
        assertNull(stats.lastDetectorReason());
        assertEquals(0L, stats.lockedTrackId());
        assertEquals(Map.of(TrackState.TENTATIVE, 0, TrackState.CONFIRMED, 0,
                TrackState.COASTING, 0, TrackState.LOST, 0), stats.byState());
    }

    @Test
    void computesTheDutyRatioOverAKnownSequence() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        // 1 detector pass in 10 frames: exactly the 10% duty cycle the sequence describes.
        window.accept(frame(0, T0, detectorPass(DetectorReason.CADENCE, 7)));
        for (int i = 1; i < 10; i++) {
            window.accept(frame(i, T0.plusMillis(i * 100L), trackerOnly(0.4, 7)));
        }

        TrackingStats stats = window.snapshot(TrackingMode.FOLLOW);

        assertEquals(1L, stats.detectorPasses());
        assertEquals(9L, stats.trackerFrames());
        assertEquals(0.1, stats.dutyRatio(), 1e-9);
    }

    @Test
    void computesNearestRankPercentilesOverAKnownLatencySequence() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        // Latencies 1.0 .. 20.0 ms, one per frame, pushed out of order to prove sorting happens.
        for (int i = 20; i >= 1; i--) {
            window.accept(frame(20 - i, T0.plusMillis((20 - i) * 100L), trackerOnly(i, 0)));
        }

        TrackingStats stats = window.snapshot(TrackingMode.FOLLOW);

        // Nearest-rank over 20 samples: p50 = element 10 (=10.0 ms), p95 = element 19 (=19.0 ms).
        assertEquals(10.0, stats.trackerMillisP50(), 1e-9);
        assertEquals(19.0, stats.trackerMillisP95(), 1e-9);
    }

    @Test
    void reportsTheReasonOfTheMostRecentDetectorPassNotOfTheMostRecentFrame() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        window.accept(frame(0, T0, detectorPass(DetectorReason.NO_LOCK, 0)));
        window.accept(frame(1, T0.plusMillis(100), detectorPass(DetectorReason.CADENCE, 7)));
        window.accept(frame(2, T0.plusMillis(200), trackerOnly(0.4, 7)));

        assertEquals(DetectorReason.CADENCE, window.snapshot(TrackingMode.FOLLOW).lastDetectorReason());
    }

    @Test
    void reportsTheEngineAndLockActuallyServingOnTheNewestFrame() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        window.accept(frame(0, T0, trackerOnly(0.4, 7)));
        // A degradation fallback swapped the engine and dropped the lock.
        window.accept(frame(1, T0.plusMillis(100),
                new TrackingTelemetry(false, null, Duration.ofMillis(1), "ncc", 0)));

        TrackingStats stats = window.snapshot(TrackingMode.FOLLOW);

        assertEquals("ncc", stats.engineId());
        assertEquals(0L, stats.lockedTrackId());
    }

    @Test
    void countsEachTrackOnceInTheHistogramByItsNewestState() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        window.accept(frame(0, T0, trackerOnly(0.4, 7),
                tracked(7, TrackState.TENTATIVE), tracked(8, TrackState.CONFIRMED)));
        window.accept(frame(1, T0.plusMillis(100), trackerOnly(0.4, 7),
                tracked(7, TrackState.CONFIRMED), tracked(8, TrackState.LOST)));

        assertEquals(Map.of(TrackState.TENTATIVE, 0, TrackState.CONFIRMED, 1,
                        TrackState.COASTING, 0, TrackState.LOST, 1),
                window.snapshot(TrackingMode.FOLLOW).byState());
    }

    @Test
    void samplesOlderThanTheWindowStopCounting() {
        TrackingStatsWindow window = new TrackingStatsWindow(Duration.ofSeconds(2));
        window.accept(frame(0, T0, detectorPass(DetectorReason.CADENCE, 7), tracked(7, TrackState.CONFIRMED)));

        window.accept(frame(1, T0.plusSeconds(3), trackerOnly(0.4, 7)));

        TrackingStats stats = window.snapshot(TrackingMode.FOLLOW);
        assertEquals(0L, stats.detectorPasses(), "the detector pass aged out of the window");
        assertEquals(1L, stats.trackerFrames());
        assertEquals(0.0, stats.dutyRatio());
        assertNull(stats.lastDetectorReason());
        assertEquals(0, stats.byState().get(TrackState.CONFIRMED));
    }

    @Test
    void aResultWithoutTrackingTelemetryIsNotCountedAtAll() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);

        // Tracking off for this stream: the 5-arg convenience ctor leaves tracking null.
        window.accept(new DetectionResult(streamId, 0, T0, List.of(), Duration.ZERO, null, null, List.of(), Optional.empty()));

        TrackingStats stats = window.snapshot(TrackingMode.OFF);
        assertEquals(0L, stats.detectorPasses());
        assertEquals(0L, stats.trackerFrames());
        assertEquals(0.0, stats.dutyRatio(), "an untracked stream reports an empty window, not a 100% duty ratio");
    }

    @Test
    void clearEmptiesTheWindow() {
        TrackingStatsWindow window = new TrackingStatsWindow(WINDOW);
        window.accept(frame(0, T0, detectorPass(DetectorReason.ALWAYS, 0)));

        window.clear();

        assertEquals(0L, window.snapshot(TrackingMode.ASSOCIATE).detectorPasses());
    }

    @Test
    void aNonPositiveWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TrackingStatsWindow(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new TrackingStatsWindow(Duration.ofSeconds(-1)));
    }
}
