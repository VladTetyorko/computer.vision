package com.drones.vision.application;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.VideoSourcePort;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * The one implementation of {@link StreamService}: one supervised pipeline per stream.
 *
 * <p>Two indexes are kept — by stream id (what is running) and by device id (so a device cannot
 * be started twice, and so a device's stream can be found when it is deactivated or deleted).
 * Both are concurrent maps; {@code putIfAbsent} on the device index is what makes double-start
 * safe under concurrent calls rather than merely unlikely.
 *
 * <p>A stream refuses to start for a device that is not in service. That check lives here rather
 * than only in a controller so every path into streaming — asset-level start, a future scheduler,
 * a test — honours deactivation.
 *
 * <h2>Threading</h2>
 * Safe for concurrent use. A failed start unwinds both indexes before rethrowing, so a botched
 * attempt never leaves a device permanently marked as streaming.
 */
public final class DefaultStreamService implements StreamService {

    private final DeviceRepositoryPort deviceRepository;
    private final VideoSourceRegistry videoSourceRegistry;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final UsageTracker usageTracker;
    private final OverlayPort overlayPort;

    private final ConcurrentHashMap<StreamId, RunningStream> activeStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<DeviceId, StreamId> streamByDevice = new ConcurrentHashMap<>();

    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, null, null);
    }

    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, null);
    }

    /**
     * Same as the 7-argument constructor, plus an {@link OverlayPort} collaborator threaded into
     * every {@link StreamPipeline} this service starts (docs/MVP1-PLAN.md §C8 bullet 2).
     *
     * @param overlayPort nullable, following the same convention as {@code usageTracker}: {@code
     *                     null} means no overlay rendering, exactly today's behavior.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker,
                                 OverlayPort overlayPort) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.videoSourceRegistry = Objects.requireNonNull(videoSourceRegistry, "videoSourceRegistry must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.usageTracker = usageTracker; // nullable: no-op usage tracking when absent
        this.overlayPort = overlayPort; // nullable: no overlay rendering when absent
    }

    @Override
    public StreamId start(DeviceId deviceId, PipelineConfig config) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(config, "config must not be null");

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Unknown device: " + deviceId.value()));
        if (!device.isActive()) {
            throw new IllegalStateException("Device is not in service: " + device.name());
        }

        StreamId streamId = StreamId.random();
        if (streamByDevice.putIfAbsent(deviceId, streamId) != null) {
            throw new IllegalStateException("Device already has an active stream: " + deviceId.value());
        }

        try {
            VideoSourcePort source = videoSourceRegistry.sourceFor(device.stream());
            Flow.Publisher<VideoFrame> publisher = source.open(streamId, device.stream());
            StreamPipeline pipeline = new StreamPipeline(streamId, device, config, publisher, detectionPort,
                    streamPublisherPort, detectionRepositoryPort, eventPublisher, overlayPort);
            activeStreams.put(streamId, new RunningStream(deviceId, source, pipeline, Instant.now()));
            pipeline.start();
            eventPublisher.publish(Event.of(streamId, EventType.STREAM_STARTED,
                    "Stream started for device " + device.name()));
            if (usageTracker != null) {
                usageTracker.onStreamStarted(deviceId);
            }
            return streamId;
        } catch (RuntimeException e) {
            activeStreams.remove(streamId);
            streamByDevice.remove(deviceId, streamId);
            throw e;
        }
    }

    @Override
    public void stop(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.remove(streamId);
        if (active == null) {
            return; // unknown or already-stopped stream: no-op, per the interface contract
        }
        streamByDevice.remove(active.deviceId(), streamId);
        active.pipeline().close();
        active.source().close(streamId);
        eventPublisher.publish(Event.of(streamId, EventType.STREAM_STOPPED, "Stream stopped"));
        if (usageTracker != null) {
            usageTracker.onStreamStopped(active.deviceId());
        }
    }

    @Override
    public List<ActiveStream> streams() {
        return activeStreams.entrySet().stream()
                .map(e -> new ActiveStream(e.getKey(), e.getValue().deviceId(), e.getValue().startedAt()))
                .toList();
    }

    @Override
    public Set<DeviceId> activeDeviceIds() {
        return Set.copyOf(streamByDevice.keySet());
    }

    /** What this service holds per running stream; distinct from the {@link ActiveStream} read model. */
    private record RunningStream(DeviceId deviceId, VideoSourcePort source, StreamPipeline pipeline,
                                  Instant startedAt) {
    }
}
