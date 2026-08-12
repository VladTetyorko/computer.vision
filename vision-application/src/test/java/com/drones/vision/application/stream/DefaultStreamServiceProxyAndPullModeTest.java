package com.drones.vision.application.stream;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.PulledDetectionPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.VideoSourcePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import com.drones.vision.application.pipeline.PullDetectionSettings;
import com.drones.vision.application.pipeline.StreamPipelineSettings;
import com.drones.vision.application.pipeline.VideoSourceRegistry;

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

    private static Flow.Publisher<com.drones.vision.domain.model.DetectionResult> noOpResultPublisher() {
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
                com.drones.vision.domain.model.EventRuleConfig.defaults(), false);

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
