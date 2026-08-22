package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.BoundingBox;
import com.drones.vision.kernel.Capability;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PullTelemetry;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Wave M5's own "done" bar (docs/plans/active/MEDIA-SOT-PLAN.md &sect;8): a hand-faked {@link PulledDetectionPort}
 * drives storage, events and the rate/latency read models identically to push mode, and push-mode
 * detection ({@link DetectionPort}) is never touched by a pull-mode pipeline. {@link
 * StreamPipelineTest} stays untouched — every one of its constructors defaults {@code pullDetection}
 * to {@code null}, so this file is purely additive coverage for the new driver.
 */
class StreamPipelinePullModeTest {

    private static final Flow.Publisher<VideoFrame> NO_OP_SOURCE = subscriber -> { };

    private static final Flow.Subscription NOOP_SUBSCRIPTION = new Flow.Subscription() {
        @Override
        public void request(long n) {
            // manually-driven test never relies on re-request
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
    private FakePulledDetectionPort pulledDetectionPort;
    private SubmissionPublisher<DetectionResult> results;

    @BeforeEach
    void setUp() {
        streamId = StreamId.random();
        device = new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("rtsp", URI.create("rtsp://camera/live"), Map.of()));
        detectionPort = mock(DetectionPort.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);
        eventPublisher = mock(EventPublisherPort.class);
        pulledDetectionPort = new FakePulledDetectionPort();
        results = new SubmissionPublisher<>(Runnable::run, Integer.MAX_VALUE);
    }

    /**
     * {@code detectionEnabled=true} stated explicitly, not left to default: {@link
     * PipelineConfig#DEFAULT_DETECTION_ENABLED} is {@code false} since wave D1, and every test in
     * this class below the gate section relies on results actually being forwarded, so the intent
     * ("this stream's detection gate is open") must be on the page rather than inherited silently.
     */
    private static PipelineConfig config() {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, Set.of(),
                EventRuleConfig.defaults(), true);
    }

    private static PipelineConfig configWithDetectionEnabled(boolean detectionEnabled) {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, Set.of(),
                EventRuleConfig.defaults(), detectionEnabled);
    }

    private StreamPipeline pullPipeline(PipelineConfig config, PullDetectionBinding binding) {
        return new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, null, null, System::nanoTime,
                StreamPipelineSettings.defaults(), binding);
    }

    private StreamPipeline pullPipeline(PipelineConfig config, PullDetectionBinding binding, AssetId assetId,
                                         DetectionLiveUpdatePort liveUpdatePublisherPort) {
        return new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, assetId, liveUpdatePublisherPort, null,
                System::nanoTime, StreamPipelineSettings.defaults(), binding);
    }

    private DetectionResult resultWithPullTelemetry(long sequence, Instant capturedAt, PullTelemetry telemetry) {
        Detection detection = new Detection("person", 0.9, new BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo26n.pt", "latest"));
        return new DetectionResult(streamId, sequence, capturedAt, List.of(detection), Duration.ofMillis(7), null,
                telemetry);
    }

    // --- the driver seam: pull results reach the same fan-out push mode uses -------------------

    private static final PullTelemetry SOME_TELEMETRY = new PullTelemetry(4L, 9.9f, 9.5f, 0L, 0L, 0L);

    @Test
    void pullResultsPersistAndAnnounceExactlyLikePushResults() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();

        Instant capturedAt = Instant.now();
        DetectionResult result = resultWithPullTelemetry(0, capturedAt, SOME_TELEMETRY);
        results.submit(result);

        assertEquals(1, pipeline.latestDetections().size());
        verify(detectionRepositoryPort).save(result);
        verify(eventPublisher).publish(argThatEvent(EventType.DETECTION));
    }

    @Test
    void pullResultsPublishLiveUpdatesExactlyLikePushResults() {
        AssetId assetId = AssetId.random();
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding, assetId, liveUpdatePublisherPort);
        pipeline.start();

        DetectionResult result = resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY);
        results.submit(result);

        verify(liveUpdatePublisherPort).publishDetections(assetId, result);
    }

    @Test
    void pullModeNeverCallsThePushDetectionPortEvenWhenAVideoFrameArrives() {
        // Regression guard for the seam itself: onNext must not fall through to maybeDetect's
        // push-submission path just because a video frame happens to flow (the JVM+pull combo).
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();
        pipeline.onSubscribe(NOOP_SUBSCRIPTION); // NO_OP_SOURCE never calls onSubscribe itself
        pipeline.onNext(new VideoFrame(streamId, 0, Instant.now(), 64, 48,
                com.drones.vision.perception.domain.model.PixelFormat.JPEG, java.nio.ByteBuffer.wrap(new byte[]{1})));

        verifyNoInteractions(detectionPort);
    }

    // --- the rate/latency read models, in pull mode (§7) ----------------------------------------

    @Test
    void detectionRateMirrorsTheWorkersSelfReportedFiguresInPullMode() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();

        PullTelemetry telemetry = new PullTelemetry(4L, 9.9f, 9.5f, 2L, 1L, 15L);
        results.submit(resultWithPullTelemetry(0, Instant.now(), telemetry));

        DetectionRate rate = pipeline.detectionRate();
        assertEquals(DetectionRate.TRANSPORT_PULL, rate.transport());
        assertEquals(9.9, rate.sourceFps(), 1e-6);
        assertEquals(9.5, rate.submittedFps(), 1e-6);
        assertEquals(2L, rate.droppedInFlight());
        assertEquals(1L, rate.missedDeadlines());
        assertEquals(0L, rate.droppedOutage());
        assertEquals(4.0, rate.decodeMillisP50(), 1e-6);
    }

    @Test
    void pushModeStillReportsTransportPushAndZeroDecodeMillis() {
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config(), NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        DetectionRate rate = pipeline.detectionRate();
        assertEquals(DetectionRate.TRANSPORT_PUSH, rate.transport());
        assertEquals(0.0, rate.decodeMillisP50(), 1e-9);
    }

    @Test
    void pipelineLatencyInPullModeIsReceivedAtMinusCapturedAt() {
        Instant capturedAt = Instant.parse("2026-08-12T10:00:00Z");
        Instant receivedAt = capturedAt.plusMillis(208);
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, () -> receivedAt);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();

        results.submit(resultWithPullTelemetry(0, capturedAt, SOME_TELEMETRY));

        PipelineLatency latency = pipeline.pipelineLatency();
        assertEquals(1L, latency.samples());
        assertEquals(208.0, latency.roundTripMillisP50(), 1e-6);
    }

    // --- PATCH .../config keeps working in pull mode (item 7) -----------------------------------

    @Test
    void updateConfigRestatesTheNewConfigOverReconfigureInsteadOfAFrameRequest() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();

        PipelineConfig next = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.6, 15, 2, Set.of());
        pipeline.updateConfig(next);

        assertEquals(1, pulledDetectionPort.reconfigureCalls.size());
        assertEquals(next, pulledDetectionPort.reconfigureCalls.get(0));
        verifyNoInteractions(detectionPort);
    }

    @Test
    void pushModeUpdateConfigNeverThrowsWithNoPulledPortWired() {
        // pullDetection defaults to null for every existing constructor/caller -- updateConfig's new
        // "if (pullDetection != null) reconfigure(...)" branch must be a true no-op in that case.
        StreamPipeline pipeline = new StreamPipeline(streamId, device, config(), NO_OP_SOURCE, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> pipeline.updateConfig(
                new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.6, 15, 2, Set.of())));
    }

    // --- the detection gate reaches pull mode too (docs/plans/active/CV-DEMAND-PLAN.md &sect;5, the ---
    // --- "one honest gap" closed at the application layer -- push mode's own gate applied here)   ---

    @Test
    void aResultIsDroppedWhenDetectionIsDisabled() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(configWithDetectionEnabled(false), binding);
        pipeline.start();
        assertEquals(DetectionState.OFF, pipeline.detectionState());

        results.submit(resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY));

        assertEquals(List.of(), pipeline.latestDetections());
        assertEquals(0L, pipeline.detectionRate().submitted());
        verifyNoInteractions(detectionRepositoryPort);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void aResultIsDroppedWhenNobodyIsWatching() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding); // detectionEnabled=true
        pipeline.start();
        pipeline.updateDetectionDemand(false);
        assertEquals(DetectionState.IDLE_NO_VIEWERS, pipeline.detectionState());

        results.submit(resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY));

        assertEquals(List.of(), pipeline.latestDetections());
        assertEquals(0L, pipeline.detectionRate().submitted());
        verify(detectionRepositoryPort, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void aResultForwardsNormallyWhenBothGatesAreOpen() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding); // detectionEnabled=true, demand defaults true
        pipeline.start();
        assertEquals(DetectionState.RUNNING, pipeline.detectionState());

        DetectionResult result = resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY);
        results.submit(result);

        assertEquals(1, pipeline.latestDetections().size());
        assertEquals(1L, pipeline.detectionRate().submitted());
        verify(detectionRepositoryPort).save(result);
    }

    @Test
    void togglingDetectionBackOnResumesForwardingOnTheVeryNextResult() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(configWithDetectionEnabled(false), binding);
        pipeline.start();

        results.submit(resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY));
        assertEquals(List.of(), pipeline.latestDetections(), "gated off: first result must be dropped");

        pipeline.updateConfig(configWithDetectionEnabled(true));
        DetectionResult resumed = resultWithPullTelemetry(1, Instant.now(), SOME_TELEMETRY);
        results.submit(resumed);

        assertEquals(1, pipeline.latestDetections().size(), "gate reopened: the very next result must forward");
        verify(detectionRepositoryPort).save(resumed);
        verify(detectionRepositoryPort, never()).save(org.mockito.ArgumentMatchers.argThat(
                r -> r != null && r.frameSequence() == 0));
    }

    @Test
    void togglingDemandBackOnResumesForwardingOnTheVeryNextResult() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding); // detectionEnabled=true
        pipeline.start();
        pipeline.updateDetectionDemand(false);

        results.submit(resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY));
        assertEquals(List.of(), pipeline.latestDetections(), "undemanded: first result must be dropped");

        pipeline.updateDetectionDemand(true);
        DetectionResult resumed = resultWithPullTelemetry(1, Instant.now(), SOME_TELEMETRY);
        results.submit(resumed);

        assertEquals(1, pipeline.latestDetections().size(), "demand restored: the very next result must forward");
        verify(detectionRepositoryPort).save(resumed);
    }

    // --- docs/plans/active/CV-DEMAND-PLAN.md §5/§7: closing the gate clears what it already served,
    // in pull mode too -- the coordinator's correction to this file's own first cut, which only
    // proved gated-off results are dropped and left an established result's *prior* boxes standing.
    // A frozen pull-mode result is exactly as dishonest a poll response as a frozen push-mode one:
    // CLAUDE.md §9, "newest data ... should be used, even if previous is still available." ---

    @Test
    void closingTheDetectionEnabledGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding); // detectionEnabled=true
        pipeline.start();

        DetectionResult established = resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY);
        results.submit(established);
        assertEquals(1, pipeline.latestDetections().size(), "boxes must be established before the gate closes");
        assertEquals(1L, pipeline.detectionRate().submitted());

        pipeline.updateConfig(configWithDetectionEnabled(false));

        assertEquals(List.of(), pipeline.latestDetections(),
                "turning detection off must not leave the last pull-mode boxes standing to be re-served");
        assertEquals(0L, pipeline.detectionRate().submitted(),
                "the rate window must not keep reporting a stale submitted count once the state reads OFF");

        pipeline.updateConfig(configWithDetectionEnabled(true));
        DetectionResult resumed = resultWithPullTelemetry(1, Instant.now(), SOME_TELEMETRY);
        results.submit(resumed);

        assertEquals(1, pipeline.latestDetections().size(), "fresh results must flow again once the gate reopens");
        verify(detectionRepositoryPort).save(resumed);
    }

    @Test
    void closingTheDetectionDemandGateClearsEstablishedBoxesAndTheRateWindowThenReopeningResumesFreshDetection() {
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding); // detectionEnabled=true
        pipeline.start();

        DetectionResult established = resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY);
        results.submit(established);
        assertEquals(1, pipeline.latestDetections().size(), "boxes must be established before the gate closes");

        pipeline.updateDetectionDemand(false);

        assertEquals(List.of(), pipeline.latestDetections(),
                "IDLE_NO_VIEWERS must not keep re-serving the last viewer's pull-mode boxes");
        assertEquals(0L, pipeline.detectionRate().submitted());

        pipeline.updateDetectionDemand(true);
        DetectionResult resumed = resultWithPullTelemetry(1, Instant.now(), SOME_TELEMETRY);
        results.submit(resumed);

        assertEquals(1, pipeline.latestDetections().size(),
                "a returning viewer must get boxes from fresh inference, not a stale snapshot");
        verify(detectionRepositoryPort).save(resumed);
    }

    @Test
    void repeatedlyConfirmingDemandIsStillGoneDoesNotCorruptTheEdgeTrackingNeededToReopenLater() {
        // Mirrors StreamPipelineTest's push-mode equivalent: the demand-poll scheduler re-confirms
        // "still nobody watching" on every tick, not just once on the transition. The shared
        // handleDetectionGateTransition() must key off the true->false edge, not the level.
        PullDetectionBinding binding = new PullDetectionBinding(pulledDetectionPort, results, Instant::now);
        StreamPipeline pipeline = pullPipeline(config(), binding);
        pipeline.start();
        results.submit(resultWithPullTelemetry(0, Instant.now(), SOME_TELEMETRY));

        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionDemand(false);
        pipeline.updateDetectionDemand(false);
        assertEquals(List.of(), pipeline.latestDetections());

        pipeline.updateDetectionDemand(true);
        DetectionResult resumed = resultWithPullTelemetry(1, Instant.now(), SOME_TELEMETRY);
        results.submit(resumed);

        assertEquals(1, pipeline.latestDetections().size(),
                "repeated false confirmations must not prevent the gate from recognising the later true");
    }

    private static Event argThatEvent(EventType type) {
        return org.mockito.ArgumentMatchers.argThat(e -> e != null && e.type() == type);
    }

    /** Hand-fake {@link PulledDetectionPort} — records {@code reconfigure}/{@code close} calls; {@code open} is
     * never exercised by these tests, since {@link PullDetectionBinding#results()} is driven directly. */
    private static final class FakePulledDetectionPort implements PulledDetectionPort {
        final List<PipelineConfig> reconfigureCalls = new ArrayList<>();
        final List<StreamId> closeCalls = new ArrayList<>();

        @Override
        public Flow.Publisher<DetectionResult> open(StreamId id, URI sourceUrl, PipelineConfig config) {
            throw new UnsupportedOperationException("test drives PullDetectionBinding#results() directly");
        }

        @Override
        public void reconfigure(StreamId id, PipelineConfig config) {
            reconfigureCalls.add(config);
        }

        @Override
        public void attitude(StreamId id, CameraAttitude attitude) {
            // not exercised by these tests
        }

        @Override
        public void close(StreamId id) {
            closeCalls.add(id);
        }
    }
}
