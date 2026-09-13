package com.drones.vision.perception.application.pipeline;

import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.port.PulledDetectionPort;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link DetectionGate} alone (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.9/K3, wave W8.2
 * extraction) — real {@link FrameSampler}/{@link OutageSupervisor} collaborators driven into a
 * chosen state via a fake clock, no {@link StreamPipeline}. Every scenario here reproduces a
 * behavior {@code StreamPipeline}'s own (pre-W8.2) inline {@code maybeDetect} classification had,
 * ported unchanged; see that class's git history for the pre-extraction version this is traced
 * against.
 *
 * <p><b>{@code GateReason#CV_UNAVAILABLE} is deliberately out of scope here</b>, for the same
 * reason it is out of scope for {@link OutageSupervisorTest}: {@link DetectionGate#classify} cannot
 * produce it. That classification is {@code StreamPipeline#submitDetection}'s own peek at whether
 * {@code detectionPort.detect} returned an already-failed {@link java.util.concurrent.CompletionStage}
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.4) — decided strictly after a {@link
 * GateVerdict.Send} leaves this class, on a port this class never calls. See {@link
 * DetectionGate}'s own class javadoc, "What stays on StreamPipeline, and why".
 */
class DetectionGateTest {

    private static final double HFOV_DEGREES = 60.0;
    private static final int MAX_IN_FLIGHT = 2;
    private static final int INFERENCE_FPS = 10; // 100ms sample interval

    private static PipelineConfig configWith(boolean detectionEnabled) {
        PipelineConfig d = PipelineConfig.defaults();
        return new PipelineConfig(d.model(), d.confidenceThreshold(), INFERENCE_FPS, MAX_IN_FLIGHT,
                d.labelFilter(), d.eventRule(), detectionEnabled, d.tracking(), d.labelDenyFilter(), d.trace());
    }

    private static DetectionRateController noopRateController() {
        return new DetectionRateController(AdaptiveRateSettings.disabled(), HFOV_DEGREES);
    }

    private static DetectionRateWindow rateWindow() {
        return new DetectionRateWindow(Duration.ofSeconds(30));
    }

    private static FrameSampler sampler(LongSupplier clock) {
        return new FrameSampler(StreamPipelineSettings.defaults(), clock);
    }

    private static OutageSupervisor supervisorWith(LongSupplier clock, long backoffInitialNanos, long backoffMaxNanos) {
        StreamPipelineSettings d = StreamPipelineSettings.defaults();
        StreamPipelineSettings settings = new StreamPipelineSettings(d.assumedSourceFps(), d.measuredFpsEwmaAlpha(),
                d.warmupFrames(), d.minMeasuredFps(), d.maxMeasuredFps(), backoffInitialNanos, backoffMaxNanos,
                d.sourceReopenBackoffInitialNanos(), d.sourceReopenBackoffMaxNanos(), d.trackingStatsWindow(),
                d.trackRetention(), d.trackingSeed(), d.cameraHfovDegrees(), d.adaptiveRate(),
                d.detectionDemandPollInterval(), d.detectionDemandGrace(), d.videoStaleAfter(), d.renderTier(),
                d.gateLedgerDepth(), d.frameLedgerDepth());
        return new OutageSupervisor(settings, clock);
    }

    private static Flow.Publisher<DetectionResult> noopPublisher() {
        return subscriber -> { };
    }

    // -- classify(): one GateReason (or Send) per scenario, isolated -----------------------------

    @Test
    void pullModeSkipsRegardlessOfEverythingElse() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        PullDetectionBinding pullDetection = new PullDetectionBinding(mock(PulledDetectionPort.class),
                noopPublisher(), Instant::now);

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), new AtomicInteger(0), pullDetection);

        assertEquals(Optional.of(new GateVerdict.Skip(GateReason.PULL_MODE, new DemandSnapshot(true, true, false))),
                verdict);
    }

    @Test
    void detectionDisabledSkipsWithGateOff() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(false);
        DetectionGate gate = new DetectionGate(config);

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), new AtomicInteger(0), null);

        assertEquals(Optional.of(new GateVerdict.Skip(GateReason.GATE_OFF, new DemandSnapshot(false, true, false))),
                verdict);
    }

    @Test
    void noDemandAndNoAlwaysOnSkipsWithGateNoDemand() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionDemand(false);

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), new AtomicInteger(0), null);

        assertEquals(
                Optional.of(new GateVerdict.Skip(GateReason.GATE_NO_DEMAND, new DemandSnapshot(true, false, false))),
                verdict);
    }

    @Test
    void alwaysOnAloneIsEnoughDemandEvenWithNoViewer() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionDemand(false);
        gate.updateDetectionPolicy(true);

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), new AtomicInteger(0), null);

        assertEquals(Optional.of(new GateVerdict.Send(false, new DemandSnapshot(true, false, true))), verdict,
                "ALWAYS opt-in alone is enough demand to submit even with no viewer");
    }

    @Test
    void outageProbeIsSentRegardlessOfTheSampleDeadline() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        OutageSupervisor supervisor = supervisorWith(clock, 100_000_000L, 400_000_000L);
        supervisor.recordFailure(false); // enters outage
        clock.advance(100_000_000L); // backoff elapsed -- next outageDecision() is PROBE

        // A fresh sampler that has never armed a deadline -- the probe branch must never consult it.
        Optional<GateVerdict> verdict = gate.classify(config, clock.getAsLong(), sampler(clock), supervisor,
                INFERENCE_FPS, noopRateController(), rateWindow(), new AtomicInteger(0), null);

        assertEquals(Optional.of(new GateVerdict.Send(true, new DemandSnapshot(true, true, false))), verdict);
    }

    @Test
    void outageBackoffSkipsOnADueDeadlineAndProducesNoVerdictBeforeIt() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        FrameSampler sampler = sampler(clock);
        // Backoff (500ms) far longer than the 100ms sample interval, so the two deadlines never coincide.
        OutageSupervisor supervisor = supervisorWith(clock, 500_000_000L, 2_000_000_000L);
        supervisor.recordFailure(false); // enters outage -- outageDecision() is SKIP until 500ms

        // The first deadline (t=0) always arms and is due -- the original inline code's own "one
        // entry per deadline" comment, exercised here through OUTAGE_BACKOFF.
        Optional<GateVerdict> first = gate.classify(config, 0L, sampler, supervisor, INFERENCE_FPS,
                noopRateController(), rateWindow(), new AtomicInteger(0), null);
        assertEquals(
                Optional.of(new GateVerdict.Skip(GateReason.OUTAGE_BACKOFF, new DemandSnapshot(true, true, false))),
                first);

        // Before the next 100ms sample deadline (and well before the 500ms backoff): no verdict at
        // all -- not even a skip -- matching the original's "no gate ledger entry, no rate-window
        // entry" for every frame arriving inside a backoff window between deadlines.
        clock.advance(50_000_000L);
        Optional<GateVerdict> second = gate.classify(config, clock.getAsLong(), sampler, supervisor, INFERENCE_FPS,
                noopRateController(), rateWindow(), new AtomicInteger(0), null);
        assertEquals(Optional.empty(), second);
    }

    @Test
    void deadlineNotYetDueSkipsWithDeadlineNotDue() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        FrameSampler sampler = sampler(clock);
        OutageSupervisor supervisor = supervisorWith(clock, 100_000_000L, 400_000_000L); // never fails -- NORMAL throughout

        Optional<GateVerdict> first = gate.classify(config, 0L, sampler, supervisor, INFERENCE_FPS,
                noopRateController(), rateWindow(), new AtomicInteger(0), null);
        assertEquals(Optional.of(new GateVerdict.Send(false, new DemandSnapshot(true, true, false))), first,
                "the first deadline arms and is due");

        clock.advance(50_000_000L); // half the 100ms interval
        Optional<GateVerdict> second = gate.classify(config, clock.getAsLong(), sampler, supervisor, INFERENCE_FPS,
                noopRateController(), rateWindow(), new AtomicInteger(0), null);

        assertEquals(
                Optional.of(new GateVerdict.Skip(GateReason.DEADLINE_NOT_DUE, new DemandSnapshot(true, true, false))),
                second);
    }

    @Test
    void inFlightBoundSkipsWithInFlightFullEvenOnADueDeadline() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        AtomicInteger inFlight = new AtomicInteger(MAX_IN_FLIGHT); // already at the bound

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), inFlight, null);

        assertEquals(
                Optional.of(new GateVerdict.Skip(GateReason.IN_FLIGHT_FULL, new DemandSnapshot(true, true, false))),
                verdict);
    }

    @Test
    void normalSampleOnADueDeadlineWithRoomInFlightSends() {
        SettableClock clock = new SettableClock(0L);
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);

        Optional<GateVerdict> verdict = gate.classify(config, 0L, sampler(clock),
                supervisorWith(clock, 100_000_000L, 400_000_000L), INFERENCE_FPS, noopRateController(),
                rateWindow(), new AtomicInteger(0), null);

        assertEquals(Optional.of(new GateVerdict.Send(false, new DemandSnapshot(true, true, false))), verdict);
    }

    // -- detectionGateOpen()/liveGateOpen(): the demand/policy truth table ------------------------

    @Test
    void demandAloneOpensBothGates() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config); // demand=true, alwaysOn=false by default

        assertTrue(gate.detectionGateOpen(config));
        assertTrue(gate.liveGateOpen(config));
    }

    @Test
    void alwaysOnAloneOpensDetectionGateButNotLiveGateRunningUnwatched() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionDemand(false);
        gate.updateDetectionPolicy(true);

        assertTrue(gate.detectionGateOpen(config), "ALWAYS keeps inference open with no viewer");
        assertFalse(gate.liveGateOpen(config), "live gate never widens on ALWAYS -- RUNNING_UNWATCHED");
    }

    @Test
    void demandAndAlwaysOnTogetherOpenBothGates() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionPolicy(true); // demand stays true (default)

        assertTrue(gate.detectionGateOpen(config));
        assertTrue(gate.liveGateOpen(config));
    }

    @Test
    void neitherDemandNorAlwaysOnClosesBothGates() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionDemand(false);

        assertFalse(gate.detectionGateOpen(config));
        assertFalse(gate.liveGateOpen(config));
    }

    @Test
    void detectionDisabledClosesBothGatesRegardlessOfDemandOrPolicy() {
        PipelineConfig config = configWith(false);
        DetectionGate gate = new DetectionGate(config); // demand=true, alwaysOn=false defaults
        gate.updateDetectionPolicy(true);

        assertFalse(gate.detectionGateOpen(config));
        assertFalse(gate.liveGateOpen(config));
    }

    // -- handleTransition(): live-gate vs detection-gate independence ------------------------------

    @Test
    void bothGatesClosingTogetherReportsOnlyInferenceClosed() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config); // both open

        gate.updateDetectionDemand(false); // alwaysOn stays false -- both close together

        assertEquals(DetectionGate.Edge.INFERENCE_CLOSED, gate.handleTransition(config));
    }

    @Test
    void liveGateClosingAloneWhileAlwaysOnKeepsInferenceOpenReportsLiveClosed() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config); // demand=true, alwaysOn=false -- both open
        gate.updateDetectionPolicy(true); // ALWAYS opts in while a viewer is already watching
        assertEquals(DetectionGate.Edge.NONE, gate.handleTransition(config),
                "opting into ALWAYS while already open changes neither gate's observed state");

        gate.updateDetectionDemand(false); // the last viewer leaves; ALWAYS keeps inference open

        assertEquals(DetectionGate.Edge.LIVE_CLOSED, gate.handleTransition(config));
        assertTrue(gate.detectionGateOpen(config), "ALWAYS keeps inference open");
        assertFalse(gate.liveGateOpen(config), "live gate closed on demand alone");
    }

    @Test
    void repeatedCloseIsANoOpNotAReClearingThrash() {
        PipelineConfig config = configWith(true);
        DetectionGate gate = new DetectionGate(config);
        gate.updateDetectionDemand(false);
        assertEquals(DetectionGate.Edge.INFERENCE_CLOSED, gate.handleTransition(config));

        assertEquals(DetectionGate.Edge.NONE, gate.handleTransition(config),
                "already closed -- a repeated false demand-poll tick must not re-clear");
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
