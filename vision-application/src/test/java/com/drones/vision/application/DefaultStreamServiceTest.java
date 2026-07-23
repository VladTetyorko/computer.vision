package com.drones.vision.application;

import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

        verify(streamPublisherPort).streamEnded(streamId);
        verify(videoSourcePort).close(streamId);

        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(eventPublisher, times(2)).publish(captor.capture());
        assertEquals(EventType.STREAM_STARTED, captor.getAllValues().get(0).type());
        assertEquals(EventType.STREAM_STOPPED, captor.getAllValues().get(1).type());
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
