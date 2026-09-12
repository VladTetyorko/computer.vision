package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.warehouse.domain.model.Device;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Drives {@link StreamPipeline#maybeDetect}/{@link StreamPipeline} directly (the production code
 * path, not hand-built {@link GateDecision} objects — see {@link FrameGateLedgerTest} for the
 * ledger's own ring/coalescing behavior in isolation) through every one of {@link GateReason}'s
 * seven values and both non-{@code SKIPPED} {@link GateOutcome}s, so the wave's "all seven reasons
 * show up in the gate ledger" acceptance bullet (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.4) is demonstrated against real classification logic, not a fixture.
 *
 * <p>A separate file from {@link StreamPipelineTest} rather than more methods appended to it
 * (mirroring the existing {@link StreamPipelinePullModeTest} precedent): this class only needs a
 * handful of {@code StreamPipeline} construction seams, and duplicating them here keeps this file
 * readable as one coherent "gate ledger" story instead of thirteen more private helpers competing
 * for space in an already-2000-line file.
 */
class StreamPipelineGateLedgerTest {

    private static final Flow.Publisher<VideoFrame> NO_OP_SOURCE = subscriber -> { };
    private static final Flow.Publisher<DetectionResult> NO_OP_PULL_RESULTS = subscriber -> { };

    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long n) {
            // manually-driven tests never rely on re-request
        }

        @Override
        public void cancel() {
            // not asserted on
        }
    };

    private Device device;
    private StreamId streamId;
    private DetectionPort detectionPort;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    private EventPublisherPort eventPublisher;

    @BeforeEach
    void setUp() {
        streamId = StreamId.random();
        device = new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()));
        detectionPort = mock(DetectionPort.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);
        eventPublisher = mock(EventPublisherPort.class);
    }

    /** {@code detectionEnabled=true} stated explicitly — every test below except the gate-off one needs it open. */
    private static PipelineConfig config(int inferenceFps, int maxInFlight) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, inferenceFps, maxInFlight, Set.of(),
                EventRuleConfig.defaults(), true);
    }

    private static PipelineConfig configWithDetectionEnabled(boolean detectionEnabled) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 10, 5, Set.of(), EventRuleConfig.defaults(),
                detectionEnabled, TrackingConfig.off(), Set.of(), false);
    }

    private VideoFrame frame(long sequence) {
        return new VideoFrame(streamId, sequence, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }

    private DetectionResult emptyResult(long sequence) {
        return new DetectionResult(streamId, sequence, Instant.now(), List.of(), java.time.Duration.ZERO, null, null,
                List.of(), Optional.empty());
    }

    /** @see StreamPipelineTest#failedFuture -- an ALREADY synchronously-completed-exceptionally future. */
    private static CompletableFuture<DetectionResult> failedFuture(String message) {
        CompletableFuture<DetectionResult> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException(message));
        return future;
    }

    /**
     * Drives the pipeline manually via {@link StreamPipeline#onNext} (bypassing {@link
     * StreamPipeline#start()}), the same seam {@link StreamPipelineTest}'s outage/backoff tests use,
     * so {@code clock} can be positioned exactly relative to a sample deadline or backoff window
     * between individual frame arrivals.
     */
    private StreamPipeline manualPipeline(PipelineConfig config, LongSupplier clock) {
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        clock, StreamPipelineSettings.defaults(), System::nanoTime, Optional.empty()));
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        return pipeline;
    }

    /** @see #manualPipeline(PipelineConfig, LongSupplier) -- the {@link GateReason#PULL_MODE} variant. */
    private StreamPipeline manualPullPipeline(PipelineConfig config, LongSupplier clock) {
        PullDetectionBinding binding = new PullDetectionBinding(mock(PulledDetectionPort.class), NO_OP_PULL_RESULTS,
                Instant::now);
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new StreamPipelineCollaborators(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        clock, StreamPipelineSettings.defaults(), System::nanoTime, Optional.of(binding)));
        pipeline.onSubscribe(NOOP_SUBSCRIPTION);
        return pipeline;
    }

    // --- GateReason.GATE_OFF -----------------------------------------------------------------

    @Test
    void gateOffIsRecordedWhenDetectionEnabledIsFalse() {
        StreamPipeline pipeline = manualPipeline(configWithDetectionEnabled(false), () -> 0L);

        pipeline.onNext(frame(0));

        verifyNoInteractions(detectionPort);
        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(1, ledger.size());
        assertEquals(GateOutcome.SKIPPED, ledger.get(0).outcome());
        assertEquals(GateReason.GATE_OFF, ledger.get(0).reason());
    }

    // --- GateReason.GATE_NO_DEMAND -------------------------------------------------------------

    @Test
    void gateNoDemandIsRecordedWhenEnabledButUndemanded() {
        StreamPipeline pipeline = manualPipeline(config(10, 5), () -> 0L);
        pipeline.updateDetectionDemand(false); // detectionDemand defaults true (fail-open); flip it off

        pipeline.onNext(frame(0));

        verifyNoInteractions(detectionPort);
        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(1, ledger.size());
        assertEquals(GateOutcome.SKIPPED, ledger.get(0).outcome());
        assertEquals(GateReason.GATE_NO_DEMAND, ledger.get(0).reason());
    }

    // --- GateReason.PULL_MODE ----------------------------------------------------------------

    @Test
    void pullModeIsRecordedWhenAPullBindingIsConfigured() {
        StreamPipeline pipeline = manualPullPipeline(config(10, 5), () -> 0L);

        pipeline.onNext(frame(0));

        verifyNoInteractions(detectionPort);
        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(1, ledger.size());
        assertEquals(GateOutcome.SKIPPED, ledger.get(0).outcome());
        assertEquals(GateReason.PULL_MODE, ledger.get(0).reason());
    }

    // --- GateReason.DEADLINE_NOT_DUE ------------------------------------------------------------

    @Test
    void deadlineNotDueIsRecordedForAFrameArrivingBeforeTheNextDeadline() {
        SettableClock clock = new SettableClock(0L);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(10, 5), clock); // 10fps -> 100ms deadline interval

        pipeline.onNext(frame(0)); // first deadline always fires (arms the schedule)
        clock.advance(1_000_000L); // 1ms: well short of the next 100ms deadline
        pipeline.onNext(frame(1));

        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(2, ledger.size());
        assertEquals(GateOutcome.SENT, ledger.get(0).outcome());
        assertEquals(GateOutcome.SKIPPED, ledger.get(1).outcome());
        assertEquals(GateReason.DEADLINE_NOT_DUE, ledger.get(1).reason());
    }

    // --- GateReason.IN_FLIGHT_FULL --------------------------------------------------------------

    @Test
    void inFlightFullIsRecordedWhenTheBoundIsAlreadySaturated() {
        SettableClock clock = new SettableClock(0L);
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes
        StreamPipeline pipeline = manualPipeline(config(1000, 1), clock); // 1ms deadline interval, maxInFlight=1

        pipeline.onNext(frame(0)); // fills the one in-flight slot
        clock.advance(2_000_000L); // 2ms: past the next deadline too
        pipeline.onNext(frame(1));

        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(2, ledger.size());
        assertEquals(GateOutcome.SENT, ledger.get(0).outcome());
        assertEquals(GateOutcome.SKIPPED, ledger.get(1).outcome());
        assertEquals(GateReason.IN_FLIGHT_FULL, ledger.get(1).reason());
    }

    // --- GateReason.CV_UNAVAILABLE ---------------------------------------------------------------

    @Test
    void cvUnavailableIsRecordedWhenTheDetectionPortRefusesSynchronously() {
        when(detectionPort.detect(any(), any())).thenReturn(failedFuture("refused"));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));

        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(1, ledger.size());
        assertEquals(GateOutcome.SKIPPED, ledger.get(0).outcome());
        assertEquals(GateReason.CV_UNAVAILABLE, ledger.get(0).reason());
    }

    // --- GateReason.OUTAGE_BACKOFF ---------------------------------------------------------------

    @Test
    void outageBackoffIsRecordedForDeadlinesSkippedWhileWaitingOutTheBackoff() {
        // Mirrors StreamPipelineTest#countsSamplesWithheldDuringADetectionOutageSeparatelyFromInFlightDrops:
        // frame(0)'s own synchronously-failed future is classified CV_UNAVAILABLE (it genuinely
        // reached detectionPort#detect), which is also what puts the pipeline into outage; every
        // later frame within the backoff window never reaches submitDetection at all, so it is
        // classified OUTAGE_BACKOFF instead -- a different reason for a different situation.
        SettableClock clock = new SettableClock(0L);
        when(detectionPort.detect(any(), any())).thenAnswer(invocation -> failedFuture("cv down"));
        StreamPipeline pipeline = manualPipeline(config(10, 5), clock);

        pipeline.onNext(frame(0)); // enters the outage
        for (long sequence = 1; sequence <= 3; sequence++) {
            clock.advance(100_000_000L); // one 10fps sample interval, still well inside the 1s backoff
            pipeline.onNext(frame(sequence));
        }

        List<GateDecision> ledger = pipeline.gateLedger(10);
        // Three identical-reason OUTAGE_BACKOFF skips coalesce into one entry (FrameGateLedger's own
        // contract) refreshed to the latest frame; the leading CV_UNAVAILABLE entry is a different
        // reason so it starts its own entry rather than being folded in.
        assertEquals(2, ledger.size());
        assertEquals(GateReason.CV_UNAVAILABLE, ledger.get(0).reason());
        assertEquals(0L, ledger.get(0).frameSequence());
        assertEquals(GateReason.OUTAGE_BACKOFF, ledger.get(1).reason());
        assertEquals(3L, ledger.get(1).frameSequence());
    }

    // --- GateOutcome.PROBE -------------------------------------------------------------------

    @Test
    void probeIsRecordedWhenAnOutageRecoveryProbeSucceeds() {
        // Mirrors StreamPipelineTest#recoveringProbeResumesDetectionResetsBackoffAndANewOutageEmitsANewEvent
        // through its first recovery: frame(1)'s probe attempt fails via an already-completed future
        // too, so it is ALSO classified CV_UNAVAILABLE (coalescing with frame(0)'s entry) rather than
        // a failed PROBE outcome -- GateOutcome.PROBE is reserved for a probe this module can actually
        // tell went out and came back, i.e. one that did not fail synchronously.
        SettableClock clock = new SettableClock(0L);
        DetectionResult recovered = emptyResult(2);
        when(detectionPort.detect(any(), any()))
                .thenReturn(failedFuture("cv down"))                       // frame(0): enters outage
                .thenReturn(failedFuture("still down"))                   // probe 1: fails synchronously too
                .thenReturn(CompletableFuture.completedFuture(recovered)); // probe 2: succeeds
        StreamPipeline pipeline = manualPipeline(config(1000, 5), clock);

        pipeline.onNext(frame(0));
        clock.advance(StreamPipeline.INITIAL_BACKOFF_NANOS);
        pipeline.onNext(frame(1));
        clock.advance(2 * StreamPipeline.INITIAL_BACKOFF_NANOS);
        pipeline.onNext(frame(2));

        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(2, ledger.size());
        assertEquals(GateReason.CV_UNAVAILABLE, ledger.get(0).reason());
        assertEquals(1L, ledger.get(0).frameSequence());
        assertEquals(GateOutcome.PROBE, ledger.get(1).outcome());
        assertEquals(2L, ledger.get(1).frameSequence());
    }

    // --- GateOutcome.SENT --------------------------------------------------------------------

    @Test
    void sentIsRecordedForANormalSuccessfulSubmission() {
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult(0)));
        StreamPipeline pipeline = manualPipeline(config(1000, 5), () -> 0L);

        pipeline.onNext(frame(0));

        verify(detectionPort, never()).detect(any(), any(), any());
        List<GateDecision> ledger = pipeline.gateLedger(10);
        assertEquals(1, ledger.size());
        assertEquals(GateOutcome.SENT, ledger.get(0).outcome());
        assertEquals(0L, ledger.get(0).frameSequence());
    }

    /** @see StreamPipelineTest.SettableClock -- duplicated here per this file's own header javadoc. */
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
