package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionDemandPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.perception.domain.port.VideoSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.drones.vision.perception.application.pipeline.DetectionEventEngine;
import com.drones.vision.perception.application.pipeline.StreamPipeline;
import com.drones.vision.perception.application.pipeline.StreamPipelineSettings;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.pipeline.VideoSourceRegistry;

class DefaultStreamServiceTest {

    private DeviceRepositoryPort deviceRepository;
    private VideoSourceRegistry videoSourceRegistry;
    private VideoSourcePort videoSourcePort;
    private DetectionPort detectionPort;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    private EventPublisherPort eventPublisher;
    private StreamService service;
    private Device device;

    /**
     * {@link PipelineConfig#defaults()} with detection explicitly turned on
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;1, wave D1 flipped {@link
     * PipelineConfig#DEFAULT_DETECTION_ENABLED} to {@code false}) — for the tests below whose actual
     * intent is that detection runs on the started stream, as distinct from the many other tests in
     * this file that only care a stream started at all and never touch {@code detectionPort}.
     */
    private static PipelineConfig detectingDefaults() {
        PipelineConfig base = PipelineConfig.defaults();
        return new PipelineConfig(base.model(), base.confidenceThreshold(), base.inferenceFps(),
                base.maxInFlightInferences(), base.labelFilter(), base.eventRule(), true, base.tracking(),
                base.labelDenyFilter());
    }

    private static Flow.Publisher<VideoFrame> noOpPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // never delivers a frame; lifecycle-only test double
            }

            @Override
            public void cancel() {
                // no-op
            }
        });
    }

    @BeforeEach
    void setUp() {
        deviceRepository = mock(DeviceRepositoryPort.class);
        videoSourceRegistry = mock(VideoSourceRegistry.class);
        videoSourcePort = mock(VideoSourcePort.class);
        detectionPort = mock(DetectionPort.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        detectionRepositoryPort = mock(DetectionRepositoryPort.class);
        eventPublisher = mock(EventPublisherPort.class);

        device = new Device(DeviceId.random(), "cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam"), Map.of()));

        when(deviceRepository.findById(device.id())).thenReturn(Optional.of(device));
        when(videoSourceRegistry.sourceFor(device.stream())).thenReturn(videoSourcePort);
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(noOpPublisher());

        service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, DefaultStreamServiceSettings.defaults());
    }

    @Test
    void startResolvesDeviceAndSourceThenEmitsStreamStarted() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertNotNull(streamId);
        verify(videoSourcePort).open(eq(streamId), eq(device.stream()));
        verify(streamPublisherPort).streamStarted(eq(streamId), eq(device));

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher).publish(captor.capture());
        assertEquals(EventType.STREAM_STARTED, captor.getValue().type());
        assertEquals(streamId, captor.getValue().streamId());
    }

    @Test
    void startThrowsForUnknownDevice() {
        DeviceId unknown = DeviceId.random();
        when(deviceRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(NoSuchElementException.class, () -> service.start(unknown, PipelineConfig.defaults()));
    }

    @Test
    void startRejectsDoubleStartForSameDevice() {
        service.start(device.id(), PipelineConfig.defaults());

        assertThrows(IllegalStateException.class, () -> service.start(device.id(), PipelineConfig.defaults()));
    }

    @Test
    void stopClosesPipelineAndSourceThenEmitsStreamStopped() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        // docs/plans/done/MVP2-PLAN.md §S, S-a: pipeline/source teardown now runs off the calling thread (see
        // DefaultStreamService.teardownAsync) so stop() itself returns promptly even when an
        // adapter's close() blocks for a long time -- verify with a bounded timeout rather than a
        // synchronous check.
        verify(streamPublisherPort, timeout(2000)).streamEnded(streamId);
        verify(videoSourcePort, timeout(2000)).close(streamId);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
        assertEquals(EventType.STREAM_STARTED, captor.getAllValues().get(0).type());
        assertEquals(EventType.STREAM_STOPPED, captor.getAllValues().get(1).type());
    }

    @Test
    void anOperatorStopCarriesTheOperatorReason() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        assertEquals("OPERATOR", stoppedEvent().attributes().get("reason"));
        assertEquals("Stream stopped", stoppedEvent().message());
    }

    @Test
    void anIdleStopSaysSoInBothTheMessageAndTheAttributes() {
        // docs/plans/done/STREAM-STATE-PLAN.md §3.2: a stop the system decided must not read as a crash.
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId, StopReason.IDLE_NO_VIEWERS);

        assertEquals(EventType.STREAM_STOPPED, stoppedEvent().type(), "still a stop, not an error");
        assertEquals("Stream stopped: no viewers", stoppedEvent().message());
        assertEquals("IDLE_NO_VIEWERS", stoppedEvent().attributes().get("reason"));
    }

    /** The second published event — every test above it starts exactly one stream first. */
    private Event stoppedEvent() {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
        return captor.getAllValues().get(1);
    }

    @Test
    void stopReturnsPromptlyEvenWhenSourceTeardownBlocksForAWhile() throws InterruptedException {
        // docs/plans/done/MVP2-PLAN.md §S, S-a: the actual reported bug -- stop() used to run
        // VideoSourcePort#close synchronously, and some adapters' close() (e.g. adapter-rtsp's
        // FfmpegVideoSource joining its native grab thread, up to 20s) blocked the calling thread
        // for that long, freezing the /live app via the browser's own per-origin connection limit.
        CountDownLatch closeStarted = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        doAnswer(invocation -> {
            closeStarted.countDown();
            assertTrue(releaseClose.await(2, TimeUnit.SECONDS), "test itself must release the latch");
            return null;
        }).when(videoSourcePort).close(any());

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        try {
            long startNanos = System.nanoTime();
            service.stop(streamId);
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertTrue(elapsedMillis < 500,
                    "stop() must return promptly even while source teardown is still blocked: " + elapsedMillis + "ms");
            assertTrue(closeStarted.await(1, TimeUnit.SECONDS), "teardown must actually run in the background");
            assertEquals(List.of(), service.streams(), "the stream must be off every listing immediately, not only once teardown finishes");
        } finally {
            releaseClose.countDown();
        }
    }

    @Test
    void sourceFailureTriggersASupervisedReopenInsteadOfEndingTheStream() {
        // docs/plans/done/MVP2-PLAN.md §S, S-a: a source error/completion alone must never end a stream -- it
        // is retried (here: a real reopen, proven by a second open() call) instead.
        ErroringThenSilentPublisher publisher = new ErroringThenSilentPublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);

        service.start(device.id(), PipelineConfig.defaults());
        publisher.failFirstSubscriber(new RuntimeException("camera unplugged"));

        verify(videoSourcePort, timeout(2000).times(2)).open(any(), eq(device.stream()));
        // The stream is still considered active -- a source failure alone never removes it.
        assertEquals(1, service.streams().size());
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, timeout(2000).atLeast(2)).publish(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(e -> e.type() == EventType.PIPELINE_ERROR),
                "a PIPELINE_ERROR must be published for the source outage");
    }

    @Test
    void explicitStopDuringBackoffCancelsThePendingRetryAndNoFurtherOpenEverHappens() {
        // docs/plans/done/MVP2-PLAN.md §S, S-a: explicit stop during backoff must cancel the pending retry
        // immediately -- a tiny backoff window (20ms) plus a generous wait afterwards proves no
        // further open() call ever arrives, without waiting out the real 1s-30s production backoff.
        StreamService fastRetryService = new DefaultStreamService(deviceRepository, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(),
                        settingsWithSourceReopenBackoff(TimeUnit.MILLISECONDS.toNanos(20),
                                TimeUnit.MILLISECONDS.toNanos(20)), Optional.empty(), Optional.empty()));
        ErroringThenSilentPublisher publisher = new ErroringThenSilentPublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);

        StreamId streamId = fastRetryService.start(device.id(), PipelineConfig.defaults());
        publisher.failFirstSubscriber(new RuntimeException("camera unplugged"));
        fastRetryService.stop(streamId);

        // Give the (now-cancelled) 20ms backoff window plenty of time to have fired if it hadn't
        // actually been cancelled.
        assertDoesNotThrow(() -> Thread.sleep(300));
        verify(videoSourcePort, times(1)).open(any(), eq(device.stream()));
    }

    /**
     * A {@link Flow.Publisher} test double whose first {@code subscribe()} call is remembered (so
     * the test can fail it asynchronously via {@link #failFirstSubscriber}) and whose every
     * subsequent {@code subscribe()} call (i.e. every supervised retry) just completes {@code
     * onSubscribe} and otherwise stays silent -- enough to prove a reopen happened without ever
     * delivering a frame.
     */
    private static final class ErroringThenSilentPublisher implements Flow.Publisher<VideoFrame> {
        private final AtomicInteger subscribeCount = new AtomicInteger();
        private volatile Flow.Subscriber<? super VideoFrame> firstSubscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super VideoFrame> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // never delivers a frame; lifecycle-only test double
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
            if (subscribeCount.getAndIncrement() == 0) {
                firstSubscriber = subscriber;
            }
        }

        void failFirstSubscriber(Throwable t) {
            firstSubscriber.onError(t);
        }
    }

    @Test
    void stopOnUnknownStreamIsANoOp() {
        assertDoesNotThrow(() -> service.stop(StreamId.random()));
        verifyNoInteractions(streamPublisherPort);
        verifyNoInteractions(videoSourcePort);
    }

    @Test
    void deviceCanStartAgainAfterStopping() {
        StreamId first = service.start(device.id(), PipelineConfig.defaults());
        service.stop(first);

        StreamId second = service.start(device.id(), PipelineConfig.defaults());

        assertNotEquals(first, second);
    }

    @Test
    void streamsIsEmptyWhenNoStreamsAreActive() {
        assertEquals(List.of(), service.streams());
    }

    @Test
    void streamsReportsActiveStreamAfterStart() {
        Instant before = Instant.now();
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        Instant after = Instant.now();

        List<ActiveStream> streams = service.streams();

        assertEquals(1, streams.size());
        ActiveStream active = streams.get(0);
        assertEquals(streamId, active.streamId());
        assertEquals(device.id(), active.deviceId());
        assertFalse(active.startedAt().isBefore(before));
        assertFalse(active.startedAt().isAfter(after));
    }

    @Test
    void streamsNoLongerReportsStreamAfterStop() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        assertEquals(List.of(), service.streams());
    }

    @Test
    void latestFrameIsEmptyForAnUnknownStream() {
        assertEquals(Optional.empty(), service.latestFrame(StreamId.random()));
    }

    @Test
    void latestFrameDelegatesToTheRunningStreamsPipeline() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertEquals(Optional.of(frame), service.latestFrame(streamId));
    }

    @Test
    void latestFrameIsEmptyOnceAStreamHasBeenStopped() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>());
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        assertEquals(Optional.empty(), service.latestFrame(streamId));
    }

    @Test
    void latestRawFrameIsEmptyForAnUnknownStream() {
        assertEquals(Optional.empty(), service.latestRawFrame(StreamId.random()));
    }

    @Test
    void latestRawFrameDelegatesToTheRunningStreamsPipeline() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>()); // never completes

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertEquals(Optional.of(frame), service.latestRawFrame(streamId));
    }

    @Test
    void latestRawFrameIsEmptyOnceAStreamHasBeenStopped() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>());
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        assertEquals(Optional.empty(), service.latestRawFrame(streamId));
    }

    @Test
    void latestDetectionsIsEmptyForAnUnknownStream() {
        assertEquals(List.of(), service.latestDetections(StreamId.random()));
    }

    @Test
    void latestDetectionsDelegatesToTheRunningStreamsPipeline() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        Detection detection = new Detection("person", 0.9,
                new com.drones.vision.kernel.BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo26n.pt", "latest"));
        DetectionResult result = new DetectionResult(frame.streamId(), 0, Instant.now(), List.of(detection),
                Duration.ZERO);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        StreamId streamId = service.start(device.id(), detectingDefaults());

        assertEquals(List.of(detection), service.latestDetections(streamId));
    }

    @Test
    void latestDetectionsIsEmptyOnceAStreamHasBeenStopped() {
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        Detection detection = new Detection("person", 0.9,
                new com.drones.vision.kernel.BoundingBox(0.1, 0.1, 0.2, 0.2),
                new ModelRef("yolo26n.pt", "latest"));
        DetectionResult result = new DetectionResult(frame.streamId(), 0, Instant.now(), List.of(detection),
                Duration.ZERO);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        assertEquals(List.of(), service.latestDetections(streamId));
    }

    @Test
    void resolvesTheOwningAssetOnceAtStartAndThreadsItWithLiveUpdatePublisherIntoThePipeline() {
        // docs/plans/done/REALTIME-PLAN.md §4: DefaultStreamService is where assetId gets resolved (via
        // usageTracker.resolveAsset), once, and handed to StreamPipeline alongside
        // liveUpdatePublisherPort -- StreamPipelineTest itself proves the emission logic once both
        // are present, this proves the wiring seam that gets them there.
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        when(usageTracker.resolveAsset(device.id())).thenReturn(Optional.of(assetId));
        DetectionLiveUpdatePort liveUpdatePublisherPort = mock(DetectionLiveUpdatePort.class);
        StreamService withLiveUpdates = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(),
                        Optional.of(liveUpdatePublisherPort), StreamPipelineSettings.defaults(), Optional.empty(),
                        Optional.empty()));
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        DetectionResult result = new DetectionResult(frame.streamId(), 0, Instant.now(), List.of(), Duration.ZERO);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        withLiveUpdates.start(device.id(), detectingDefaults());

        verify(liveUpdatePublisherPort).publishDetections(assetId, result);
    }

    @Test
    void neverResolvesOrAnnouncesLiveUpdatesWhenNoLiveUpdatePublisherIsConfigured() {
        // Documents/protects the nullable-collaborator contract: usageTracker.resolveAsset must
        // never even be called when there's no DetectionLiveUpdatePort to hand the result to and no
        // camera field of view configured that could ever read a telemetry supplier built from it
        // (see the telemetry-supplier tests below).
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService withTracker = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(), Optional.empty(),
                        StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty()));

        withTracker.start(device.id(), PipelineConfig.defaults());

        verify(usageTracker, never()).resolveAsset(any());
    }

    @Test
    void threadsATelemetrySupplierWhenAFieldOfViewIsConfigured() {
        // INVARIANT (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1): the telemetry supplier that feeds
        // CameraAttitude/ego-motion is built from usageTracker + a configured field of view alone --
        // it once shipped dead because its construction was gated on an OverlayPort being present
        // (docs/conclusions/CV-RATE-BUDGET.md gap 3), which silently meant a deployment with a
        // configured field of view but no overlay sent no CameraPose at all. Now that overlay does
        // not exist anywhere in this constructor chain, this test pins that camera attitude still
        // flows with nothing overlay-shaped anywhere in the call.
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        when(usageTracker.resolveAsset(device.id())).thenReturn(Optional.of(assetId));
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(
                new Telemetry(device.id(), Instant.now(), 50.0, 30.0, 120.0, 137.5, 80.0, Map.of())));
        StreamPipelineSettings withFov = settingsWithCameraHfov(62.0);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(), Optional.empty(),
                        withFov, Optional.empty(), Optional.empty()));
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        DetectionResult result = new DetectionResult(frame.streamId(), 0, Instant.now(), List.of(), Duration.ZERO);
        when(detectionPort.detect(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        service.start(device.id(), detectingDefaults());

        ArgumentCaptor<CameraAttitude> captor = ArgumentCaptor.forClass(CameraAttitude.class);
        verify(detectionPort).detect(any(), any(), captor.capture());
        assertEquals(137.5, captor.getValue().yawDegrees());
        assertEquals(62.0, captor.getValue().hfovDegrees());
    }

    @Test
    void buildsNoTelemetrySupplierWithNoFieldOfViewConfigured() {
        // The cost gate the fix had to preserve: nothing reads telemetry, so nothing resolves it.
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(), Optional.empty(),
                        StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty()));

        service.start(device.id(), PipelineConfig.defaults());

        verify(usageTracker, never()).resolveAsset(any());
        verify(detectionPort, never()).detect(any(), any(), any());
    }

    private static StreamPipelineSettings settingsWithCameraHfov(double hfovDegrees) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(),
                base.warmupFrames(), base.minMeasuredFps(), base.maxMeasuredFps(),
                base.detectionBackoffInitialNanos(), base.detectionBackoffMaxNanos(),
                base.sourceReopenBackoffInitialNanos(), base.sourceReopenBackoffMaxNanos(),
                base.extrapolationMaxMillis(), base.extrapolationMatchGate(),
                base.trackingStatsWindow(), base.trackRetention(), base.trackingSeed(), hfovDegrees);
    }

    /**
     * docs/plans/done/MVP2-PLAN.md §S, S-a: {@link StreamPipelineSettings#defaults()} with a tiny
     * (typically 20ms) source reopen backoff instead of production's real 1s-30s one, so
     * supervision-related tests don't have to wait it out.
     */
    private static StreamPipelineSettings settingsWithSourceReopenBackoff(long initialNanos, long maxNanos) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(), base.warmupFrames(),
                base.minMeasuredFps(), base.maxMeasuredFps(), base.detectionBackoffInitialNanos(),
                base.detectionBackoffMaxNanos(), initialNanos, maxNanos, base.extrapolationMaxMillis(),
                base.extrapolationMatchGate(), base.trackingStatsWindow(), base.trackRetention(),
                base.trackingSeed());
    }

    /** A {@link Flow.Publisher} that delivers exactly one frame on its first {@code request()} call. */
    private static Flow.Publisher<VideoFrame> framePublisher(VideoFrame frame) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean delivered;

            @Override
            public void request(long n) {
                if (!delivered) {
                    delivered = true;
                    subscriber.onNext(frame);
                }
            }

            @Override
            public void cancel() {
                // no-op
            }
        });
    }

    @Test
    void startNotifiesUsageTrackerWhenOneIsConfigured() {
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService withTracker = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(), Optional.empty(),
                        StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty()));

        StreamId streamId = withTracker.start(device.id(), PipelineConfig.defaults());

        verify(usageTracker).onStreamStarted(device.id(), streamId);
    }

    @Test
    void stopNotifiesUsageTrackerWhenOneIsConfigured() {
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService withTracker = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.of(usageTracker), Optional.empty(), Optional.empty(),
                        StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty()));
        StreamId streamId = withTracker.start(device.id(), PipelineConfig.defaults());

        withTracker.stop(streamId);

        verify(usageTracker).onStreamStopped(device.id());
    }

    @Test
    void usageTrackerIsNeverTouchedWhenNoneIsConfigured() {
        // service (built in setUp) uses the six-argument constructor: no UsageTracker at all.
        // Nothing to verify a mock against here -- this test documents/protects the nullable-collaborator
        // contract by asserting start/stop still work without one (a NullPointerException would fail it).
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        assertDoesNotThrow(() -> service.stop(streamId));
    }

    @Test
    void startWithADetectionEventRepositoryPortConfiguredDoesNotThrow() {
        // docs/plans/done/MVP2-PLAN.md §E, E-a: the nine-argument constructor threads a fresh, per-stream
        // DetectionEventEngine into every StreamPipeline it starts. Full rule-engine behavior is
        // covered by DetectionEventEngineTest/StreamPipelineTest; this is the wiring seam,
        // mirroring usageTrackerIsNeverTouchedWhenNoneIsConfigured's "must not NPE" style for the
        // opposite (configured) direction.
        DetectionEventRepositoryPort detectionEventRepositoryPort = mock(DetectionEventRepositoryPort.class);
        StreamService withEvents = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.of(detectionEventRepositoryPort),
                        Optional.empty(), StreamPipelineSettings.defaults(), Optional.empty(), Optional.empty()));

        StreamId streamId = withEvents.start(device.id(), PipelineConfig.defaults());

        assertDoesNotThrow(() -> withEvents.stop(streamId));
    }

    @Test
    void startWithoutADetectionEventRepositoryPortNeverConstructsAnEventEngine() {
        // service (built in setUp) uses the six-argument constructor: no DetectionEventRepositoryPort
        // at all. Nothing to verify a mock against here -- documents/protects the same
        // nullable-collaborator contract as usageTrackerIsNeverTouchedWhenNoneIsConfigured.
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        assertDoesNotThrow(() -> service.stop(streamId));
    }

    // --- docs/plans/done/CV-CONTROL-PLAN.md Wave C: StreamService.updateConfig ---

    /**
     * A {@link Flow.Publisher} whose frames are pushed explicitly by the test via {@link #push},
     * rather than in response to {@code request()} — lets a test call {@link
     * StreamService#updateConfig} between two frames of the same running stream and observe which
     * config a later frame's {@code detect()} call carried.
     */
    private static final class ControllableFramePublisher implements Flow.Publisher<VideoFrame> {
        private volatile Flow.Subscriber<? super VideoFrame> subscriber;

        @Override
        public void subscribe(Flow.Subscriber<? super VideoFrame> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // frames are pushed explicitly via push(), not in response to request()
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });
        }

        void push(VideoFrame frame) {
            subscriber.onNext(frame);
        }
    }

    private static VideoFrame frameOn(StreamId streamId, long sequence) {
        return new VideoFrame(streamId, sequence, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
    }

    private static DetectionResult emptyResultOn(StreamId streamId) {
        return new DetectionResult(streamId, 0, Instant.now(), List.of(), Duration.ZERO);
    }

    @Test
    void updateConfigThrowsForAnUnknownStream() {
        assertThrows(NoSuchElementException.class,
                () -> service.updateConfig(StreamId.random(), PipelineConfigPatch.NOTHING));
    }

    @Test
    void updateConfigThrowsForAStoppedStream() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        service.stop(streamId);

        assertThrows(NoSuchElementException.class, () -> service.updateConfig(streamId, PipelineConfigPatch.NOTHING));
    }

    @Test
    void updateConfigThrowsIllegalArgumentForAnInvalidMergedValue() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig(streamId, new PipelineConfigPatch(1.5, null, null, null, null)));
    }

    @Test
    void updateConfigAppliesAHotKnobToTheRunningPipelineAndReportsNoReArm() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));
        // detectionEnabled explicit (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor default):
        // this test's whole point is that detection keeps running across the hot-knob swap.
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        StreamId streamId = service.start(device.id(), started);

        UpdateOutcome outcome = service.updateConfig(streamId, new PipelineConfigPatch(0.75, null, null, null, null));

        assertFalse(outcome.modelReArmed());
        publisher.push(frameOn(streamId, 0));

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort).detect(any(), captor.capture());
        assertEquals(0.75, captor.getValue().confidenceThreshold());
        assertEquals("yolo", captor.getValue().model().id());
    }

    @Test
    void updateConfigReportsNoReArmWhenThePatchNeverMentionsTheModel() {
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        UpdateOutcome outcome = service.updateConfig(streamId, new PipelineConfigPatch(0.6, null, null, null, null));

        assertFalse(outcome.modelReArmed());
    }

    @Test
    void updateConfigChangingTheModelIdReportsReArmedAndAppliesTheNewModelKeepingItsVersion() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));
        // detectionEnabled explicit (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor default):
        // this test's whole point is that the next sampled frame still detects, on the new model.
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        StreamId streamId = service.start(device.id(), started);

        UpdateOutcome outcome =
                service.updateConfig(streamId, new PipelineConfigPatch(null, null, null, null, "orion12l.pt"));

        assertTrue(outcome.modelReArmed());
        publisher.push(frameOn(streamId, 0));

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort).detect(any(), captor.capture());
        assertEquals("orion12l.pt", captor.getValue().model().id());
        assertEquals("latest", captor.getValue().model().version(), "model version is not PATCH-able");
    }

    @Test
    void updateConfigMergesOnlyThePresentFieldLeavingEveryOtherFieldExactlyAsItWas() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));
        // detectionEnabled explicit (docs/plans/done/CV-DEMAND-PLAN.md §1 flipped the convenience-ctor default):
        // this test's whole point is that detection keeps running with the merged config.
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo", "v1"), 0.4, 1000, 3, Set.of("person"),
                EventRuleConfig.defaults(), true);
        StreamId streamId = service.start(device.id(), started);

        service.updateConfig(streamId, new PipelineConfigPatch(null, null, Set.of("car"), null, null));
        publisher.push(frameOn(streamId, 0));

        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort).detect(any(), captor.capture());
        PipelineConfig merged = captor.getValue();
        assertEquals(Set.of("car"), merged.labelFilter());
        assertEquals(0.4, merged.confidenceThreshold());
        assertEquals(1000, merged.inferenceFps());
        assertEquals(3, merged.maxInFlightInferences());
        assertTrue(merged.labelDenyFilter().isEmpty(), "labelDenyFilter is untouched by this patch");
        assertEquals("yolo", merged.model().id());
        assertEquals("v1", merged.model().version());
        assertTrue(merged.detectionEnabled());
    }

    // --- docs/plans/done/TRACKING-PLAN.md §4.D, wave T3: the tracking fold and server-allocated lockSeq ---

    private static TrackingConfig tracking(TrackingMode mode, TargetLock lock) {
        return new TrackingConfig(mode, "lk", 2000, 15, 30, 30, 3, lock);
    }

    /**
     * A patch stating <b>every</b> tracking field — what a client that sends the whole object looks
     * like. The partial-patch cases below each build their own {@link TrackingConfigPatch} inline,
     * naming only the knob under test, because that is the shape the UI actually sends.
     */
    private static PipelineConfigPatch trackingPatch(TrackingConfig requested) {
        return trackingPatch(new TrackingConfigPatch(requested.mode(), requested.engineId(),
                requested.verifyEveryMillis(), requested.followFps(), requested.redetectIouPercent(),
                requested.maxAgeFrames(), requested.minHits(), requested.lock()));
    }

    private static PipelineConfigPatch trackingPatch(TrackingConfigPatch requested) {
        return new PipelineConfigPatch(null, null, null, null, null, requested);
    }

    /** A patch naming exactly one knob, every other field left {@code null} ("unchanged"). */
    private static PipelineConfigPatch onlyVerifyEveryMillis(int millis) {
        return trackingPatch(new TrackingConfigPatch(null, null, millis, null, null, null, null, null));
    }

    private static PipelineConfigPatch onlyFollowFps(int fps) {
        return trackingPatch(new TrackingConfigPatch(null, null, null, fps, null, null, null, null));
    }

    private static PipelineConfigPatch onlyEngineId(String engineId) {
        return trackingPatch(new TrackingConfigPatch(null, engineId, null, null, null, null, null, null));
    }

    private static PipelineConfig startedWith(TrackingConfig tracking) {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, Set.of(),
                EventRuleConfig.defaults(), true, tracking);
    }

    /**
     * Starts a stream on a publisher the test drives frame by frame, so {@link #runningTracking} can
     * read back exactly what the pipeline is configured with — the same idiom the {@code
     * updateConfig} tests above already use to observe a merged config.
     */
    private StreamId startCapturable(ControllableFramePublisher publisher, PipelineConfig started) {
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));
        return service.start(device.id(), started);
    }

    /** Bound on {@link #runningTracking}'s push loop — a stuck sampler must fail, not hang. */
    private static final Duration SAMPLE_WAIT = Duration.ofSeconds(2);

    /**
     * Pushes frames until one serves a sample deadline, and returns the {@link TrackingConfig} it
     * carried into {@code detect}.
     *
     * <p>Sampling is deadline-based (docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R1) and this test
     * pushes frames microseconds apart, so a push made right after a config change usually lands
     * inside the previous sample interval and is correctly held back. Pushing until one lands keeps
     * this helper about the config it set out to observe rather than about the sampler's cadence.
     */
    private TrackingConfig runningTracking(ControllableFramePublisher publisher, StreamId streamId, long sequence) {
        int before = detectInvocationCount();
        long waitUntilNanos = System.nanoTime() + SAMPLE_WAIT.toNanos();
        while (System.nanoTime() < waitUntilNanos) {
            publisher.push(frameOn(streamId, sequence));
            if (detectInvocationCount() > before) {
                ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
                verify(detectionPort, atLeastOnce()).detect(any(), captor.capture());
                return captor.getValue().tracking();
            }
        }
        throw new AssertionError("no pushed frame served a sample deadline within " + SAMPLE_WAIT);
    }

    private int detectInvocationCount() {
        return (int) mockingDetails(detectionPort).getInvocations().stream()
                .filter(invocation -> "detect".equals(invocation.getMethod().getName()))
                .count();
    }

    @Test
    void aPatchWithoutTrackingLeavesTheRunningTrackingConfigUntouched() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        TrackingConfig running = tracking(TrackingMode.FOLLOW, new TargetLock(4, 7L, null, null, false));
        StreamId streamId = startCapturable(publisher, startedWith(running));

        UpdateOutcome outcome = service.updateConfig(streamId, new PipelineConfigPatch(0.75, null, null, null, null));

        assertFalse(outcome.trackingChanged());
        assertEquals(running, runningTracking(publisher, streamId, 0));
    }

    @Test
    void aTrackingPatchAppliesWithoutEverReArmingTheDetector() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher, startedWith(TrackingConfig.off()));

        UpdateOutcome outcome = service.updateConfig(streamId, trackingPatch(tracking(TrackingMode.FOLLOW, null)));

        assertTrue(outcome.trackingChanged());
        assertFalse(outcome.modelReArmed(), "tracking is a hot knob like confidence and fps");
        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(TrackingMode.FOLLOW, applied.mode());
        assertEquals("lk", applied.engineId());
    }

    @Test
    void restatingTheIdenticalTrackingConfigIsNotAChange() {
        StreamId streamId = service.start(device.id(), startedWith(TrackingConfig.defaults()));

        UpdateOutcome outcome = service.updateConfig(streamId, trackingPatch(TrackingConfig.defaults()));

        assertFalse(outcome.trackingChanged());
    }

    @Test
    void lockSeqIsAllocatedByTheServerAndIncrementsPerLockNeverDecreasing() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher, startedWith(TrackingConfig.off()));

        // Every patch below carries lockSeq 0, exactly as a client always does.
        service.updateConfig(streamId,
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, 7L, null, null, false))));
        TargetLock first = runningTracking(publisher, streamId, 0).lock();

        service.updateConfig(streamId,
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, 9L, null, null, false))));
        TargetLock second = runningTracking(publisher, streamId, 1).lock();

        service.updateConfig(streamId,
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, null, null, null, true))));
        TargetLock third = runningTracking(publisher, streamId, 2).lock();

        assertEquals(1L, first.lockSeq(), "the server allocates, so a client's 0 never reaches the pipeline");
        assertEquals(7L, first.trackId());
        assertEquals(2L, second.lockSeq());
        assertEquals(9L, second.trackId());
        assertEquals(3L, third.lockSeq());
        assertTrue(third.release(), "a release is a lock request too, and takes the next sequence");
    }

    @Test
    void aReplayedIdenticalLockStillGetsAFreshSequenceSoItIsARequestNotAResurrection() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher, startedWith(TrackingConfig.off()));
        PipelineConfigPatch sameLock =
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, 7L, null, null, false)));

        service.updateConfig(streamId, sameLock);
        UpdateOutcome second = service.updateConfig(streamId, sameLock);

        assertEquals(2L, runningTracking(publisher, streamId, 0).lock().lockSeq());
        assertTrue(second.trackingChanged(), "a re-issued lock is a new request, and its sequence says so");
    }

    @Test
    void aTrackingPatchWithoutALockKeepsTheRunningLockAndBurnsNoSequenceNumber() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher, startedWith(TrackingConfig.off()));
        service.updateConfig(streamId,
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, 7L, null, null, false))));

        // A pure cadence tweak must never drop the operator's target, nor advance the sequence.
        service.updateConfig(streamId, trackingPatch(
                new TrackingConfig(TrackingMode.FOLLOW, "lk", 1000, 15, 30, 30, 3, null)));

        TargetLock lock = runningTracking(publisher, streamId, 0).lock();
        assertEquals(7L, lock.trackId());
        assertEquals(1L, lock.lockSeq());
    }

    @Test
    void lockSequencesAreScopedPerStreamNotShared() {
        ControllableFramePublisher first = new ControllableFramePublisher();
        ControllableFramePublisher secondPublisher = new ControllableFramePublisher();
        Device second = new Device(DeviceId.random(), "cam2", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam2"), Map.of()));
        when(deviceRepository.findById(second.id())).thenReturn(Optional.of(second));
        when(videoSourceRegistry.sourceFor(second.stream())).thenReturn(videoSourcePort);
        when(videoSourcePort.open(any(), eq(second.stream()))).thenReturn(secondPublisher);
        StreamId a = startCapturable(first, startedWith(TrackingConfig.off()));
        StreamId b = service.start(second.id(), startedWith(TrackingConfig.off()));
        PipelineConfigPatch lock =
                trackingPatch(tracking(TrackingMode.FOLLOW, new TargetLock(0, 7L, null, null, false)));

        service.updateConfig(a, lock);
        service.updateConfig(a, lock);
        service.updateConfig(b, lock);

        assertEquals(2L, runningTracking(first, a, 0).lock().lockSeq());
        assertEquals(1L, runningTracking(secondPublisher, b, 0).lock().lockSeq(),
                "one operator's clicks must not advance another stream's sequence");
    }

    // --- the per-field fold: the UI sends one knob at a time, and every other knob must survive ---

    @Test
    void twoSuccessiveCadencePatchesKeepBothCadences() {
        // The regression this fold exists for: the verify-cadence and follow-fps sliders ship side by
        // side, so moving one and then the other used to revert the first.
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher, startedWith(tracking(TrackingMode.FOLLOW, null)));

        service.updateConfig(streamId, onlyVerifyEveryMillis(5000));
        service.updateConfig(streamId, onlyFollowFps(25));

        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(5000, applied.verifyEveryMillis(), "the first slider's value must survive the second");
        assertEquals(25, applied.followFps());
    }

    @Test
    void aPatchCarryingOnlyTheEngineLeavesTheModeEveryCadenceAndTheLockUntouched() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        TrackingConfig running = new TrackingConfig(TrackingMode.FOLLOW, "lk", 5000, 25, 45, 60, 2,
                new TargetLock(4, 7L, null, null, false));
        StreamId streamId = startCapturable(publisher, startedWith(running));

        UpdateOutcome outcome = service.updateConfig(streamId, onlyEngineId("ncc"));

        assertTrue(outcome.trackingChanged());
        assertEquals(new TrackingConfig(TrackingMode.FOLLOW, "ncc", 5000, 25, 45, 60, 2, running.lock()),
                runningTracking(publisher, streamId, 0),
                "an engine swap states one field; the other seven are the stream's own state");
    }

    @Test
    void aLockReleaseLeavesTheModeAtFollow() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        StreamId streamId = startCapturable(publisher,
                startedWith(tracking(TrackingMode.FOLLOW, new TargetLock(1, 7L, null, null, false))));

        service.updateConfig(streamId, trackingPatch(new TrackingConfigPatch(null, null, null, null, null, null, null,
                new TargetLock(0, null, null, null, true))));

        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(TrackingMode.FOLLOW, applied.mode(), "releasing a target is not leaving FOLLOW");
        assertTrue(applied.lock().release());
        assertEquals(1L, applied.lock().lockSeq(), "the release takes the stream's next sequence number");
    }

    // --- docs/extracts/TRACKING-ORCHESTRATION.md §4.1: the deployment seed is applied here, for every start path ---

    @Test
    void aStartThatStatesNothingAboutTrackingTakesTheDeploymentSeed() {
        StreamService seeded = serviceSeededWith(
                new TrackingConfigPatch(TrackingMode.ASSOCIATE, null, 2500, 20, null, null, null, null));
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));

        StreamId streamId = seeded.start(device.id(), detectingDefaults());

        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(TrackingMode.ASSOCIATE, applied.mode(), "the deployment default reaches every start path");
        assertEquals(2500, applied.verifyEveryMillis());
        assertEquals(20, applied.followFps());
        assertEquals(TrackingConfig.DEFAULT_MAX_AGE_FRAMES, applied.maxAgeFrames(),
                "a knob the deployment does not own falls through to the domain's own literal");
    }

    @Test
    void aStartRequestsOwnTrackingWinsOverTheDeploymentSeed() {
        StreamService seeded = serviceSeededWith(
                new TrackingConfigPatch(TrackingMode.ASSOCIATE, null, 2500, 20, null, null, null, null));
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));

        StreamId streamId = seeded.start(device.id(), detectingDefaults(),
                new TrackingConfigPatch(TrackingMode.FOLLOW, "lk", null, null, null, null, null, null));

        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(TrackingMode.FOLLOW, applied.mode(), "request > deployment > code default");
        assertEquals("lk", applied.engineId());
        assertEquals(2500, applied.verifyEveryMillis(), "what the request leaves unsaid still comes from the seed");
    }

    /** A service whose {@link StreamPipelineSettings#trackingSeed()} is {@code seed}. */
    private StreamService serviceSeededWith(TrackingConfigPatch seed) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        StreamPipelineSettings seeded = new StreamPipelineSettings(base.assumedSourceFps(),
                base.measuredFpsEwmaAlpha(), base.warmupFrames(), base.minMeasuredFps(), base.maxMeasuredFps(),
                base.detectionBackoffInitialNanos(), base.detectionBackoffMaxNanos(),
                base.sourceReopenBackoffInitialNanos(), base.sourceReopenBackoffMaxNanos(),
                base.extrapolationMaxMillis(), base.extrapolationMatchGate(), base.trackingStatsWindow(),
                base.trackRetention(), seed);
        return new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(), seeded,
                        Optional.empty(), Optional.empty()));
    }

    @Test
    void tracksAndTrackingStatsAreEmptyForAnUnknownOrStoppedStream() {
        StreamId unknown = StreamId.random();
        assertEquals(List.of(), service.tracks(unknown));
        assertEquals(Optional.empty(), service.trackingStats(unknown));

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        assertEquals(List.of(), service.tracks(streamId), "a running stream with no tracks yet reads empty too");
        assertTrue(service.trackingStats(streamId).isPresent());

        service.stop(streamId);
        assertEquals(List.of(), service.tracks(streamId));
        assertEquals(Optional.empty(), service.trackingStats(streamId));
    }

    // --- docs/plans/done/CV-DEMAND-PLAN.md §2, §3.3-3.4: detection-demand grace period and scheduler resilience ---

    /** {@link StreamPipelineSettings#defaults()} with its two demand tunables replaced. */
    private static StreamPipelineSettings settingsWithDetectionDemand(Duration pollInterval, Duration grace) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(),
                base.warmupFrames(), base.minMeasuredFps(), base.maxMeasuredFps(),
                base.detectionBackoffInitialNanos(), base.detectionBackoffMaxNanos(),
                base.sourceReopenBackoffInitialNanos(), base.sourceReopenBackoffMaxNanos(),
                base.extrapolationMaxMillis(), base.extrapolationMatchGate(),
                base.trackingStatsWindow(), base.trackRetention(), base.trackingSeed(),
                base.cameraHfovDegrees(), base.adaptiveRate(), pollInterval, grace);
    }

    @Test
    void demandGraceKeepsDetectingForAWhileAfterTheLastRealDemandThenStopsOnceItExpires() {
        ControllableFramePublisher publisher = new ControllableFramePublisher();
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(publisher);
        when(detectionPort.detect(any(), any())).thenAnswer(inv -> CompletableFuture.completedFuture(
                emptyResultOn(((VideoFrame) inv.getArgument(0)).streamId())));
        DetectionDemandPort demandPort = mock(DetectionDemandPort.class);
        Duration grace = Duration.ofSeconds(5);
        // A one-minute poll interval keeps the background scheduler from ever ticking on its own
        // during this test -- every evaluation below is driven explicitly through
        // evaluateDetectionDemand's package-private Instant seam, never by waiting out a real cadence.
        StreamPipelineSettings settings = settingsWithDetectionDemand(Duration.ofMinutes(1), grace);
        DefaultStreamService demandService = new DefaultStreamService(deviceRepository, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(), settings,
                        Optional.empty(), Optional.of(demandPort)));
        // inferenceFps=100 (10ms sample interval) plus the 15ms real sleeps below reliably clear the
        // pipeline's own real-nanoTime sample deadline between pushes -- unrelated to (and much
        // shorter than) the synthetic Instants driving the grace computation itself below, which
        // never waits on real time at all.
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 100, 5, Set.of(),
                EventRuleConfig.defaults(), true);
        StreamId streamId = demandService.start(device.id(), started);
        Instant t0 = Instant.now();

        when(demandPort.detectionWanted(streamId, null)).thenReturn(true);
        demandService.evaluateDetectionDemand(streamId, t0);
        publisher.push(frameOn(streamId, 0));
        verify(detectionPort, times(1)).detect(any(), any());

        // No longer wanted, but still inside the grace window -- must keep detecting.
        when(demandPort.detectionWanted(streamId, null)).thenReturn(false);
        demandService.evaluateDetectionDemand(streamId, t0.plus(grace).minusMillis(1));
        assertDoesNotThrow(() -> Thread.sleep(15));
        publisher.push(frameOn(streamId, 1));
        verify(detectionPort, times(2)).detect(any(), any());

        // Grace has just expired -- must stop.
        demandService.evaluateDetectionDemand(streamId, t0.plus(grace).plusMillis(1));
        assertDoesNotThrow(() -> Thread.sleep(15));
        publisher.push(frameOn(streamId, 2));
        verify(detectionPort, times(2)).detect(any(), any());
    }

    @Test
    void aThrowingDetectionDemandPortNeverStopsLaterScheduledEvaluations() {
        // scheduleAtFixedRate silently cancels every future run the moment its task throws uncaught --
        // pollDetectionDemand's per-stream catch(Throwable) is what stands between "one bad
        // DetectionDemandPort implementation" and detection-demand evaluation silently stopping for
        // the rest of the process.
        DetectionDemandPort throwingPort = mock(DetectionDemandPort.class);
        when(throwingPort.detectionWanted(any(), any())).thenThrow(new RuntimeException("boom"));
        // A tiny (20ms) real poll interval, same convention as
        // explicitStopDuringBackoffCancelsThePendingRetryAndNoFurtherOpenEverHappens's tiny backoff --
        // proves several ticks happen within a couple of seconds rather than waiting out the real 2s
        // production default.
        StreamPipelineSettings settings = settingsWithDetectionDemand(Duration.ofMillis(20),
                StreamPipelineSettings.defaults().detectionDemandGrace());
        DefaultStreamService demandService = new DefaultStreamService(deviceRepository, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(), settings,
                        Optional.empty(), Optional.of(throwingPort)));

        demandService.start(device.id(), PipelineConfig.defaults());

        verify(throwingPort, timeout(2000).atLeast(3)).detectionWanted(any(), any());
    }

    @Test
    void aFreshlyStartedStreamWithNoObservedDemandIsGatedOffByTheFirstEvaluationNotAfterAFullGracePeriod() {
        // Pins the coordinator follow-up fix: RunningStream#lastDemandAt is seeded to Instant.EPOCH,
        // not Instant.now(), because demand must be observed, never assumed. Before that fix, this
        // exact sequence -- start, then evaluate once with no demand ever having been wanted -- would
        // have found itself trivially "within grace" of a demand it never actually observed (lastDemandAt
        // stamped to the moment the stream started), and stayed RUNNING for a full 30s grace period it
        // never earned.
        DetectionDemandPort demandPort = mock(DetectionDemandPort.class);
        when(demandPort.detectionWanted(any(), any())).thenReturn(false);
        // A one-minute poll interval keeps the background scheduler from ever ticking on its own --
        // this test drives evaluateDetectionDemand explicitly, through its package-private Instant seam.
        StreamPipelineSettings settings = settingsWithDetectionDemand(Duration.ofMinutes(1), Duration.ofSeconds(30));
        DefaultStreamService demandService = new DefaultStreamService(deviceRepository, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher,
                new DefaultStreamServiceSettings(Optional.empty(), Optional.empty(), Optional.empty(), settings,
                        Optional.empty(), Optional.of(demandPort)));
        StreamId streamId = demandService.start(device.id(), detectingDefaults());

        // Before any evaluation at all: StreamPipeline#detectionDemand's own fail-open true default
        // still reads RUNNING -- the narrow, accepted startup window, bounded by
        // detectionDemandPollInterval (2s default) rather than a full detectionDemandGrace (30s).
        assertEquals(Optional.of(DetectionState.RUNNING), demandService.detectionState(streamId));

        // The very first evaluation ever, immediately after start, with demand never observed.
        demandService.evaluateDetectionDemand(streamId, Instant.now());

        assertEquals(Optional.of(DetectionState.IDLE_NO_VIEWERS), demandService.detectionState(streamId),
                "must gate off at once, not stay RUNNING for a grace period nothing ever earned");
    }

    // --- docs/plans/done/CV-DEMAND-PLAN.md §3.6: DetectionState -- forgiving read, delegation ---

    @Test
    void detectionStateIsEmptyForAnUnknownOrStoppedStreamAndPresentWhileRunning() {
        StreamId unknown = StreamId.random();
        assertEquals(Optional.empty(), service.detectionState(unknown));

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        assertEquals(Optional.of(DetectionState.OFF), service.detectionState(streamId),
                "PipelineConfig.defaults() ships detectionEnabled=false as of CV-DEMAND-PLAN wave D1");

        service.stop(streamId);
        assertEquals(Optional.empty(), service.detectionState(streamId));
    }
}
