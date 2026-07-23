package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PixelFormat;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.nio.ByteBuffer;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

        // docs/MVP2-PLAN.md §S, S-a: pipeline/source teardown now runs off the calling thread (see
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
        // docs/MVP2-PLAN.md §S, S-a: the actual reported bug -- stop() used to run
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
        // docs/MVP2-PLAN.md §S, S-a: a source error/completion alone must never end a stream -- it
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
        // docs/MVP2-PLAN.md §S, S-a: explicit stop during backoff must cancel the pending retry
        // immediately -- a tiny backoff window (20ms) plus a generous wait afterwards proves no
        // further open() call ever arrives, without waiting out the real 1s-30s production backoff.
        StreamService fastRetryService = new DefaultStreamService(deviceRepository, videoSourceRegistry,
                detectionPort, streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null,
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
        // docs/MVP2-PLAN.md §E, E-a: the nine-argument constructor threads a fresh, per-stream
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
}
