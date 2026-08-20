package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.OverlayPort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.perception.domain.port.VideoSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import com.drones.vision.perception.application.pipeline.PullDetectionSettings;
import com.drones.vision.perception.application.pipeline.StreamPipelineSettings;
import com.drones.vision.perception.application.pipeline.VideoSourceRegistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D4 (proxied sources open no {@code VideoSourcePort}) and the pull-mode wiring
 * (docs/plans/active/MEDIA-SOT-PLAN.md wave M5) plus {@code burnedIn} (&sect;5.4). {@link DefaultStreamServiceTest}
 * stays untouched — every one of its call sites uses the pre-existing constructors, which all default
 * {@code pullDetectionSettings} to {@code null} and get {@code proxiesSource() == false} from a plain
 * Mockito mock, so this file is purely additive coverage for the new wiring.
 */
class DefaultStreamServiceProxyAndPullModeTest {

    private DeviceRepositoryPort deviceRepository;
    private VideoSourceRegistry videoSourceRegistry;
    private VideoSourcePort videoSourcePort;
    private DetectionPort detectionPort;
    private StreamPublisherPort streamPublisherPort;
    private DetectionRepositoryPort detectionRepositoryPort;
    private EventPublisherPort eventPublisher;
    private Device device;

    private static Flow.Publisher<VideoFrame> noOpVideoPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // never delivers a frame
            }

            @Override
            public void cancel() {
                // no-op
            }
        });
    }

    private static Flow.Publisher<com.drones.vision.perception.domain.model.DetectionResult> noOpResultPublisher() {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                // never delivers a result
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
                new StreamDescriptor("rtsp", URI.create("rtsp://camera/live"), Map.of()));
        when(deviceRepository.findById(device.id())).thenReturn(Optional.of(device));
        when(videoSourceRegistry.sourceFor(device.stream())).thenReturn(videoSourcePort);
        when(videoSourcePort.open(any(), eq(device.stream()))).thenReturn(noOpVideoPublisher());
    }

    // --- D4: proxied sources open no VideoSourcePort at all -------------------------------------

    @Test
    void proxiedDeviceOpensNoVideoSourceButStillSignalsStreamStarted() {
        when(streamPublisherPort.proxiesSource(device)).thenReturn(true);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        verifyNoInteractions(videoSourceRegistry);
        verify(videoSourcePort, never()).open(any(), any());
        verify(streamPublisherPort).streamStarted(streamId, device);
    }

    @Test
    void proxiedDeviceStopsWithoutTouchingAVideoSourcePort() {
        when(streamPublisherPort.proxiesSource(device)).thenReturn(true);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        // must not throw: RunningStream#source/#supervisedSource are null for a proxied stream, and
        // both stop()/teardownAsync() guard every call against that.
        service.stop(streamId);

        verify(streamPublisherPort, org.mockito.Mockito.timeout(2_000)).streamEnded(streamId);
        verifyNoInteractions(videoSourcePort);
    }

    @Test
    void nonProxiedDeviceStillOpensAVideoSourceExactlyAsBefore() {
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        verify(videoSourcePort).open(streamId, device.stream());
    }

    // --- STREAM-STATE-PLAN S1: the state a client used to have to guess ------------------------

    /** {@link PipelineConfig#defaults()} with detection on — the record has no {@code with*} copier. */
    private static PipelineConfig detectionOn() {
        PipelineConfig base = PipelineConfig.defaults();
        return new PipelineConfig(base.model(), base.confidenceThreshold(), base.inferenceFps(),
                base.maxInFlightInferences(), base.overlayTelemetry(), base.labelFilter(), base.eventRule(),
                base.overlayBurnIn(), true, base.tracking());
    }

    @Test
    void proxiedStreamReportsUnobservedRatherThanStartingForever() {
        // The whole reason UNOBSERVED exists: this JVM opens no source, so no frame will EVER
        // arrive, and STARTING would be a state that never resolves.
        when(streamPublisherPort.proxiesSource(device)).thenReturn(true);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertEquals(Optional.of(StreamState.UNOBSERVED), service.streamState(streamId));
    }

    @Test
    void nonProxiedStreamWithNoFrameYetReportsStarting() {
        // noOpVideoPublisher() subscribes but never delivers, which is exactly the just-started window.
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertEquals(Optional.of(StreamState.STARTING), service.streamState(streamId));
    }

    @Test
    void unknownStreamHasNoStateRatherThanAGuessedOne() {
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        assertEquals(Optional.empty(), service.streamState(StreamId.random()));
    }

    @Test
    void stoppedStreamHasNoStateRatherThanAStoppedOne() {
        // Pins the deliberate absence of a STOPPED member: lifecycle is AssetUsage's axis, not this
        // enum's, so a stopped stream answers empty exactly like an unknown one.
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        service.stop(streamId);

        assertEquals(Optional.empty(), service.streamState(streamId));
    }

    @Test
    void listedStreamCarriesTheOperatorsOwnDetectionIntent() {
        // The field that ends the localStorage guess: GET /api/streams is built from this record, and
        // detectionEnabled had no read surface at all before this plan.
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        service.start(device.id(), detectionOn());

        ActiveStream listed = service.streams().getFirst();
        assertTrue(listed.detectionEnabled());
        assertEquals(StreamState.STARTING, listed.state());
    }

    @Test
    void listedStreamReportsDetectionOffWhenThatIsWhatTheStreamStartedWith() {
        // PipelineConfig.defaults() is detection-off since CV-DEMAND-PLAN wave D1; the listing must
        // report that rather than the optimistic default a convenience ctor would supply.
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);
        service.start(device.id(), PipelineConfig.defaults());

        assertFalse(service.streams().getFirst().detectionEnabled());
    }

    // --- pull-mode wiring (switch B) -------------------------------------------------------------

    @Test
    void pullModeOpensThePulledDetectionPortAgainstTheRtspBaseAndStreamId() {
        PulledDetectionPort pulledDetectionPort = mock(PulledDetectionPort.class);
        when(pulledDetectionPort.open(any(), any(), any())).thenReturn(noOpResultPublisher());
        PullDetectionSettings pullDetectionSettings =
                new PullDetectionSettings(pulledDetectionPort, URI.create("rtsp://worker-host:8554"));
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null, null,
                StreamPipelineSettings.defaults(), pullDetectionSettings);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        verify(pulledDetectionPort).open(eq(streamId),
                eq(URI.create("rtsp://worker-host:8554/" + streamId.value())), any());
    }

    @Test
    void pullModeClosesThePulledDetectionPortOnStop() {
        PulledDetectionPort pulledDetectionPort = mock(PulledDetectionPort.class);
        when(pulledDetectionPort.open(any(), any(), any())).thenReturn(noOpResultPublisher());
        PullDetectionSettings pullDetectionSettings =
                new PullDetectionSettings(pulledDetectionPort, URI.create("rtsp://worker-host:8554"));
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, null, null, null,
                StreamPipelineSettings.defaults(), pullDetectionSettings);
        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        service.stop(streamId);

        verify(pulledDetectionPort, org.mockito.Mockito.timeout(2_000)).close(streamId);
    }

    @Test
    void pushModeNeverTouchesAPulledDetectionPort() {
        // pullDetectionSettings defaults to null on every pre-existing constructor -- this pins that
        // a push-mode start/stop cycle is byte-identical (no NPE, no stray pull-port call).
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());
        service.stop(streamId);
        // no pull port was ever wired in, so there is nothing to verify against directly -- this
        // test's value is that start()/stop() complete without throwing.
    }

    // --- burnedIn (§5.4) -------------------------------------------------------------------------

    @Test
    void burnedInIsTrueForAPlainPushStreamWithOverlayConfigured() {
        OverlayPort overlayPort = mock(OverlayPort.class);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertTrue(service.burnedIn(streamId));
        assertTrue(service.streams().stream().filter(s -> s.streamId().equals(streamId)).findFirst()
                .orElseThrow().burnedIn());
    }

    @Test
    void burnedInIsFalseWithNoOverlayPortWired() {
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertFalse(service.burnedIn(streamId));
    }

    @Test
    void burnedInIsFalseWhenOverlayBurnInIsOffForTheStream() {
        OverlayPort overlayPort = mock(OverlayPort.class);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort);
        PipelineConfig noBurnIn = new PipelineConfig(PipelineConfig.defaults().model(), 0.4, 10, 2, true, Set.of(),
                com.drones.vision.perception.domain.model.EventRuleConfig.defaults(), false);

        StreamId streamId = service.start(device.id(), noBurnIn);

        assertFalse(service.burnedIn(streamId));
    }

    @Test
    void burnedInIsFalseForAProxiedStreamEvenWithOverlayConfigured() {
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(streamPublisherPort.proxiesSource(device)).thenReturn(true);
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertFalse(service.burnedIn(streamId));
    }

    @Test
    void burnedInIsTrueForAJvmPublishPullDetectionStreamWithOverlayConfigured() {
        // docs/plans/active/MEDIA-SOT-PLAN.md §5.4 defect fix: pull detection does NOT itself suppress
        // burn-in. This is the A=JVM, B=pull combination (Phase 1's V4L2/MJPEG/sim answer) -- the JVM
        // still opens a VideoSourcePort and still publishes frames through StreamPipeline#overlayIfNeeded,
        // which knows nothing about how detections arrived. The video this viewer receives genuinely
        // carries burned-in boxes, so a client that also draws canvas boxes off a false burnedIn would
        // double-render them.
        OverlayPort overlayPort = mock(OverlayPort.class);
        PulledDetectionPort pulledDetectionPort = mock(PulledDetectionPort.class);
        when(pulledDetectionPort.open(any(), any(), any())).thenReturn(noOpResultPublisher());
        PullDetectionSettings pullDetectionSettings =
                new PullDetectionSettings(pulledDetectionPort, URI.create("rtsp://worker-host:8554"));
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort, null, null,
                StreamPipelineSettings.defaults(), pullDetectionSettings);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertTrue(service.burnedIn(streamId));
        verify(videoSourcePort).open(streamId, device.stream());
    }

    @Test
    void burnedInIsFalseForAProxiedPullModeStream() {
        // A=proxy, B=pull -- the target combination for RTSP cameras (§3). Proxied alone is sufficient
        // to make burnedIn false, because in proxy mode publish() is a no-op and no JVM frame is ever
        // published for overlayIfNeeded to burn into, regardless of the detection transport.
        OverlayPort overlayPort = mock(OverlayPort.class);
        when(streamPublisherPort.proxiesSource(device)).thenReturn(true);
        PulledDetectionPort pulledDetectionPort = mock(PulledDetectionPort.class);
        when(pulledDetectionPort.open(any(), any(), any())).thenReturn(noOpResultPublisher());
        PullDetectionSettings pullDetectionSettings =
                new PullDetectionSettings(pulledDetectionPort, URI.create("rtsp://worker-host:8554"));
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher, null, overlayPort, null, null,
                StreamPipelineSettings.defaults(), pullDetectionSettings);

        StreamId streamId = service.start(device.id(), PipelineConfig.defaults());

        assertFalse(service.burnedIn(streamId));
    }

    @Test
    void burnedInIsFalseForAnUnknownStream() {
        StreamService service = new DefaultStreamService(deviceRepository, videoSourceRegistry, detectionPort,
                streamPublisherPort, detectionRepositoryPort, eventPublisher);

        assertFalse(service.burnedIn(StreamId.random()));
    }
}
