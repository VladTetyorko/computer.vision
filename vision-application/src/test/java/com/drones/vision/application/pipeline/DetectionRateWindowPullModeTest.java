package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.PullTelemetry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(0L, rate.droppedInFlight());
        assertEquals(0L, rate.missedDeadlines());
        assertEquals(0.0, rate.decodeMillisP50());
    }

    @Test
    void clearForgetsThePullFiguresToo() {
        DetectionRateWindow window = new DetectionRateWindow(WINDOW, DetectionRateWindow.Transport.PULL);
        window.recordPull(new PullTelemetry(4L, 9.9f, 9.5f, 2L, 1L, 0L), 0L);

        window.clear();

        DetectionRate rate = window.snapshot(0.0, 10.0, 0.0);
        assertEquals(0.0, rate.sourceFps());
        assertEquals(0L, rate.droppedInFlight());
        assertEquals(0L, rate.missedDeadlines());
        assertEquals(0.0, rate.decodeMillisP50());
    }

    @Test
    void aPlainOneArgumentWindowDefaultsToPushTransport() {
        DetectionRate rate = new DetectionRateWindow(WINDOW).snapshot(30.0, 10.0, 0.0);

        assertEquals(DetectionRate.TRANSPORT_PUSH, rate.transport());
        assertEquals(0.0, rate.decodeMillisP50());
    }
}
