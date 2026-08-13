package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.BoundingBox;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CameraAttitude;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PullTelemetry;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.PulledDetectionPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
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

    private static PipelineConfig config() {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, true, Set.of());
    }

    private StreamPipeline pullPipeline(PipelineConfig config, PullDetectionBinding binding) {
        return new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, null, null, null, System::nanoTime,
                StreamPipelineSettings.defaults(), binding);
    }

    private StreamPipeline pullPipeline(PipelineConfig config, PullDetectionBinding binding, AssetId assetId,
                                         LiveUpdatePublisherPort liveUpdatePublisherPort) {
        return new StreamPipeline(streamId, device, config, NO_OP_SOURCE, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, assetId, liveUpdatePublisherPort, null,
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
        LiveUpdatePublisherPort liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
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
                com.drones.vision.domain.model.PixelFormat.JPEG, java.nio.ByteBuffer.wrap(new byte[]{1})));

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

        PipelineConfig next = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.6, 15, 2, true, Set.of());
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
                new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.6, 15, 2, true, Set.of())));
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
