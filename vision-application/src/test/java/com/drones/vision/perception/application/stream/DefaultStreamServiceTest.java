package com.drones.vision.perception.application.stream;

import com.drones.vision.perception.domain.model.AnnotatedFrame;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PixelFormat;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.events.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.events.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.perception.domain.port.OverlayPort;
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
                detectionRepositoryPort, eventPublisher);
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
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null, null,
                TimeUnit.MILLISECONDS.toNanos(20), TimeUnit.MILLISECONDS.toNanos(20));
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

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

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
        LiveUpdatePublisherPort liveUpdatePublisherPort = mock(LiveUpdatePublisherPort.class);
        StreamService withLiveUpdates = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, usageTracker, null, null,
                liveUpdatePublisherPort);
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        DetectionResult result = new DetectionResult(frame.streamId(), 0, Instant.now(), List.of(), Duration.ZERO);
        when(detectionPort.detect(any(), any())).thenReturn(CompletableFuture.completedFuture(result));

        withLiveUpdates.start(device.id(), PipelineConfig.defaults());

        verify(liveUpdatePublisherPort).publishDetections(assetId, result);
    }

    @Test
    void neverResolvesOrAnnouncesLiveUpdatesWhenNoLiveUpdatePublisherIsConfigured() {
        // Documents/protects the nullable-collaborator contract: usageTracker.resolveAsset must
        // never even be called when there's neither a LiveUpdatePublisherPort to hand the result to
        // nor an OverlayPort that could ever read a telemetry supplier built from it (see the
        // telemetry-supplier tests below).
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService withTracker = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, usageTracker);

        withTracker.start(device.id(), PipelineConfig.defaults());

        verify(usageTracker, never()).resolveAsset(any());
    }

    // --- Telemetry-OSD input (closes adapter-overlay/MODULE.md's "OSD gate not reachable" gap) ---

    @Test
    void resolvesTheOwningAssetAndThreadsATelemetrySupplierIntoThePipelineWhenOverlayIsConfigured() {
        UsageTracker usageTracker = mock(UsageTracker.class);
        AssetId assetId = AssetId.random();
        when(usageTracker.resolveAsset(device.id())).thenReturn(Optional.of(assetId));
        Telemetry sample = new Telemetry(device.id(), Instant.now(), 50.45, 30.52, 100.0, 90.0, 77.0, Map.of());
        when(usageTracker.latestTelemetry(assetId)).thenReturn(Optional.of(sample));
        OverlayPort overlayPort = mock(OverlayPort.class);
        VideoFrame frame = new VideoFrame(StreamId.random(), 0, Instant.now(), 64, 48, PixelFormat.JPEG,
                ByteBuffer.wrap(new byte[]{1, 2, 3}));
        when(overlayPort.render(any())).thenReturn(frame);
        StreamService withOverlay = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, usageTracker, overlayPort);
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(framePublisher(frame));
        // Never completes: with PipelineConfig.defaults()'s overlayTelemetry=true, the telemetry
        // sample alone (no detections at all) is what must trigger overlay rendering.
        when(detectionPort.detect(any(), any())).thenReturn(new CompletableFuture<>());

        withOverlay.start(device.id(), PipelineConfig.defaults());

        ArgumentCaptor<AnnotatedFrame> captor = ArgumentCaptor.forClass(AnnotatedFrame.class);
        verify(overlayPort).render(captor.capture());
        assertEquals(sample, captor.getValue().telemetry());
    }

    @Test
    void neverBuildsATelemetrySupplierWhenNoUsageTrackerIsConfiguredEvenWithAnOverlayPort() {
        // Documents/protects the nullable-collaborator contract the other direction: an OverlayPort
        // with no UsageTracker at all must never throw building/using a telemetry supplier.
        OverlayPort overlayPort = mock(OverlayPort.class);
        StreamService withOverlayOnly = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort);

        assertDoesNotThrow(() -> withOverlayOnly.start(device.id(), PipelineConfig.defaults()));
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
                streamPublisherPort, detectionRepositoryPort, eventPublisher, usageTracker);

        StreamId streamId = withTracker.start(device.id(), PipelineConfig.defaults());

        verify(usageTracker).onStreamStarted(device.id(), streamId);
    }

    @Test
    void stopNotifiesUsageTrackerWhenOneIsConfigured() {
        UsageTracker usageTracker = mock(UsageTracker.class);
        StreamService withTracker = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, usageTracker);
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
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null,
                detectionEventRepositoryPort);

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
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, true, Set.of());
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
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 1000, 5, true, Set.of());
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
        PipelineConfig started = new PipelineConfig(new ModelRef("yolo", "v1"), 0.4, 1000, 3, true, Set.of("person"));
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
        assertTrue(merged.overlayTelemetry());
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
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 1000, 5, true, Set.of(),
                EventRuleConfig.defaults(), true, true, tracking);
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

    /** Pushes one frame and returns the {@link TrackingConfig} it carried into {@code detect}. */
    private TrackingConfig runningTracking(ControllableFramePublisher publisher, StreamId streamId, long sequence) {
        publisher.push(frameOn(streamId, sequence));
        ArgumentCaptor<PipelineConfig> captor = ArgumentCaptor.forClass(PipelineConfig.class);
        verify(detectionPort, atLeastOnce()).detect(any(), captor.capture());
        return captor.getValue().tracking();
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

        StreamId streamId = seeded.start(device.id(), PipelineConfig.defaults());

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

        StreamId streamId = seeded.start(device.id(), PipelineConfig.defaults(),
                new TrackingConfigPatch(TrackingMode.FOLLOW, "lk", null, null, null, null, null, null));

        TrackingConfig applied = runningTracking(publisher, streamId, 0);
        assertEquals(TrackingMode.FOLLOW, applied.mode(), "request > deployment > code default");
        assertEquals("lk", applied.engineId());
        assertEquals(2500, applied.verifyEveryMillis(), "what the request leaves unsaid still comes from the seed");
    }

    /** A service whose {@link StreamPipelineSettings#trackingSeed()} is {@code seed}. */
    private StreamService serviceSeededWith(TrackingConfigPatch seed) {
        StreamPipelineSettings base = StreamPipelineSettings.defaults();
        return new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort,
                detectionRepositoryPort, eventPublisher, null, null, null, null,
                new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(), base.warmupFrames(),
                        base.minMeasuredFps(), base.maxMeasuredFps(), base.detectionBackoffInitialNanos(),
                        base.detectionBackoffMaxNanos(), base.sourceReopenBackoffInitialNanos(),
                        base.sourceReopenBackoffMaxNanos(), base.extrapolationMaxMillis(),
                        base.extrapolationMatchGate(), base.trackingStatsWindow(), base.trackRetention(), seed));
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
}
