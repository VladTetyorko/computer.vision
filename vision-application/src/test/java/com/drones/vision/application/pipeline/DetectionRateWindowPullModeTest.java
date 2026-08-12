package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.PullTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pull-mode accounting (docs/plans/active/MEDIA-SOT-PLAN.md &sect;7, wave M5, D12) — the worker's own
 * self-reported figures, mirrored rather than re-derived. {@link DetectionRateWindowTest} (push mode)
 * stays untouched: this is a sibling, not a replacement.
 */
class DetectionRateWindowPullModeTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final long SECOND = 1_000_000_000L;

    @Test
    void snapshotMirrorsTheWorkersLatestSelfReportedFigures() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);

        window.recordPull(new PullTelemetry(4L, 9.9f, 9.5f, 2L, 1L, 15L), 0L);

        DetectionRate rate = window.snapshot(24.0, 10.0, 0.0);
        assertEquals(DetectionRate.TRANSPORT_PULL, rate.transport());
        // sourceFps comes from the worker's own report, not the sourceFps argument (meaningless for
        // a proxied stream that never flows through this JVM at all).
        assertEquals(9.9, rate.sourceFps(), 1e-6);
        assertEquals(9.5, rate.submittedFps(), 1e-6);
        // One recordPull call is one DetectionResult, i.e. one submission (docs/conclusions/
        // MEDIA-SOT-RESULTS.md §6) -- counted here directly, never read off the wire.
        assertEquals(1L, rate.submitted());
        assertEquals(2L, rate.droppedInFlight(), "dropped_frames is the pull analogue of an in-flight drop");
        assertEquals(1L, rate.missedDeadlines());
        assertEquals(0L, rate.droppedOutage(), "outage backoff is push-mode-only bookkeeping");
        assertEquals(4.0, rate.decodeMillisP50(), 1e-6);
        // targetFps/demandFps still come from the caller: the Java rate controller runs unchanged in
        // pull mode and its output still travels on the wire (§7).
        assertEquals(10.0, rate.targetFps());
    }

    @Test
    void laterReportsReplaceEarlierOnesRatherThanAccumulating() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);

        window.recordPull(new PullTelemetry(4L, 9.9f, 9.5f, 2L, 1L, 0L), 0L);
        window.recordPull(new PullTelemetry(6L, 9.8f, 9.4f, 5L, 3L, 0L), SECOND);

        DetectionRate rate = window.snapshot(0.0, 10.0, 0.0);
        assertEquals(9.8, rate.sourceFps(), 1e-6, "cumulative counters -- the latest restatement wins");
        assertEquals(5L, rate.droppedInFlight());
        assertEquals(3L, rate.missedDeadlines());
        // Unlike the worker-restated fields above, `submitted` is counted here, not mirrored -- two
        // recordPull calls means two DetectionResults arrived, so it accumulates rather than replacing.
        assertEquals(2L, rate.submitted());
    }

    @Test
    void decodeMillisP50IsTheMedianOverTheWindow() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);

        window.recordPull(new PullTelemetry(2L, 9f, 9f, 0L, 0L, 0L), 0L);
        window.recordPull(new PullTelemetry(4L, 9f, 9f, 0L, 0L, 0L), SECOND / 10);
        window.recordPull(new PullTelemetry(9L, 9f, 9f, 0L, 0L, 0L), 2 * SECOND / 10);

        assertEquals(4.0, window.snapshot(0.0, 0.0, 0.0).decodeMillisP50(), 1e-6);
    }

    @Test
    void reportsZeroesBeforeAnyResultArrivesInPullMode() {
        DetectionRate rate = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL)
                .snapshot(0.0, 10.0, 0.0);

        assertEquals(DetectionRate.TRANSPORT_PULL, rate.transport());
        assertEquals(0.0, rate.sourceFps());
        assertEquals(0.0, rate.submittedFps());
        assertEquals(0L, rate.submitted());
        assertEquals(0L, rate.droppedInFlight());
        assertEquals(0L, rate.missedDeadlines());
        assertEquals(0.0, rate.decodeMillisP50());
        assertEquals(0.0, rate.dropRatio(), "nothing was due yet -- must read as 0.0, not 1.0");
    }

    @Test
    void clearForgetsThePullFiguresToo() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);
        window.recordPull(new PullTelemetry(4L, 9.9f, 9.5f, 2L, 1L, 0L), 0L);

        window.clear();

        DetectionRate rate = window.snapshot(0.0, 10.0, 0.0);
        assertEquals(0.0, rate.sourceFps());
        assertEquals(0L, rate.submitted());
        assertEquals(0L, rate.droppedInFlight());
        assertEquals(0L, rate.missedDeadlines());
        assertEquals(0.0, rate.decodeMillisP50());
    }

    @Test
    void dropRatioIsNearZeroForAHealthyPullStreamNotPinnedAtOne() {
        // The M9 regression (docs/conclusions/MEDIA-SOT-RESULTS.md &sect;6): a healthy 30fps-source/
        // 10fps-target pull stream reported `submitted: 0, droppedInFlight: 1027, dropRatio: 1.0`
        // alongside `submittedFps: 9.987` -- both cannot be true. `submitted` was hard-coded to `0L`
        // in snapshotPull, so any nonzero droppedInFlight pinned dropRatio() at exactly 1.0 no matter
        // how healthy the stream actually was. Pull mode delivers one DetectionResult per inferred
        // frame, so 300 recordPull calls is what 300 real, healthy detections looks like.
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);
        for (int i = 0; i < 300; i++) {
            window.recordPull(new PullTelemetry(4L, 9.9f, 9.987f, 0L, 0L, 0L), i * (SECOND / 30));
        }

        DetectionRate rate = window.snapshot(0.0, 10.0, 0.0);
        assertEquals(300L, rate.submitted(), "one DetectionResult in pull mode is one submission");
        assertEquals(300L, rate.due());
        assertEquals(0.0, rate.dropRatio(), 1e-9, "no genuine drops were reported -- must not read as 1.0");
    }

    @Test
    void dueAndDropRatioWeighSubmittedAgainstTheWorkersGenuineDrops() {
        // A stream that genuinely cannot keep up (the worker's own dropped_frames climbing, D8's
        // backlog case -- not the downsampling case docs/conclusions/MEDIA-SOT-RESULTS.md &sect;6 also
        // fixed cv-service-side) must still show up as a real, non-1.0, non-zero ratio.
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);
        for (int i = 0; i < 90; i++) {
            long dropped = i < 89 ? 0L : 10L; // the worker restates its cumulative total on every call
            window.recordPull(new PullTelemetry(4L, 9.9f, 9.0f, dropped, 0L, 0L), i * (SECOND / 10));
        }

        DetectionRate rate = window.snapshot(0.0, 10.0, 0.0);
        assertEquals(90L, rate.submitted());
        assertEquals(10L, rate.droppedInFlight());
        assertEquals(100L, rate.due());
        assertEquals(0.10, rate.dropRatio(), 1e-9);
        assertTrue(rate.dropRatio() > 0.0 && rate.dropRatio() < 1.0);
    }

    @Test
    void aPlainOneArgumentWindowDefaultsToPushTransport() {
        DetectionRate rate = new DetectionRateWindow(WINDOW).snapshot(30.0, 10.0, 0.0);

        assertEquals(DetectionRate.TRANSPORT_PUSH, rate.transport());
        assertEquals(0.0, rate.decodeMillisP50());
    }
}
