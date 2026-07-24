package com.drones.vision.application;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionEventRepositoryPort;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.VideoSourcePort;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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
 * <h2>Source supervision (docs/MVP2-PLAN.md &sect;S, S-a)</h2>
 * {@link #start} never hands the source adapter's own {@link VideoSourcePort#open} result straight
 * to {@link StreamPipeline}; it wraps it in a {@link SupervisedPublisher} first. A started stream
 * therefore survives a source I/O error or unexpected completion on its own — {@link
 * StreamPipeline} itself is completely unaware this is happening (it only ever sees the normal
 * {@code onSubscribe}/{@code onNext} traffic the wrapper forwards), so its own detection-outage
 * machinery, {@code latestDetections()}, and the {@link com.drones.vision.domain.port.out.StreamPublisherPort}
 * session it opened via {@code streamStarted} are all completely untouched by a reconnect —
 * {@code streamStarted}/{@code streamEnded} fire exactly once each, at {@link #start}/{@link #stop}
 * respectively, never again in between. {@link UsageTracker#onStreamStarted}/{@code
 * onStreamStopped} are likewise called exactly once each per {@link #start}/{@link #stop} — a
 * reconnect never touches usage tracking at all, so the same {@code usageId} (and the {@code
 * streamId} it was opened with) stays open across every retry. Only an explicit {@link #stop} ever
 * ends a stream; see that method's own javadoc for how it cancels supervision.
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
    private final DetectionEventRepositoryPort detectionEventRepositoryPort;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;
    private final long sourceInitialBackoffNanos;
    private final long sourceMaxBackoffNanos;

    /**
     * One dedicated daemon thread scheduling every stream's supervised-reopen retries
     * (docs/MVP2-PLAN.md §S, S-a). Shared, not per-stream: a demo/small-fleet stream count keeps
     * this thread's actual work trivial (each retry just calls {@code VideoSourcePort#open}, which
     * every registered adapter today returns from quickly — see {@link SupervisedPublisher}'s own
     * javadoc), and this class has no {@code close()}/shutdown lifecycle of its own to hook a
     * per-instance teardown into; the thread is daemon so it never blocks JVM exit.
     */
    private final ScheduledExecutorService retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "stream-supervisor");
        thread.setDaemon(true);
        return thread;
    });

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
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, overlayPort, null);
    }

    /**
     * Same as the 8-argument constructor, plus a {@link DetectionEventRepositoryPort} collaborator
     * (docs/MVP2-PLAN.md §E, E-a): when present, every {@link StreamPipeline} this service starts
     * is given a fresh, per-stream {@link DetectionEventEngine} built from {@code config}'s {@link
     * com.drones.vision.domain.model.PipelineConfig#eventRule()}, {@code usageTracker} (for
     * asset/position resolution), and this port.
     *
     * @param detectionEventRepositoryPort nullable, following the same convention as {@code
     *                                      overlayPort}/{@code usageTracker}: {@code null} means
     *                                      no debounced {@code DetectionEvent} tracking on any
     *                                      stream this service starts. Deliberately the only new
     *                                      constructor parameter for this feature rather than
     *                                      duplicating {@code AssetRepositoryPort}/{@code
     *                                      AssetUsageRepositoryPort} here — {@code usageTracker}
     *                                      already holds both and now exposes the two read
     *                                      methods {@link DetectionEventEngine} needs.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker,
                                 OverlayPort overlayPort, DetectionEventRepositoryPort detectionEventRepositoryPort) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, overlayPort, detectionEventRepositoryPort, null);
    }

    /**
     * Same as the 9-argument constructor, plus a {@link LiveUpdatePublisherPort} collaborator
     * (docs/REALTIME-PLAN.md §4): threaded into every {@link StreamPipeline} this service starts,
     * alongside the owning asset id resolved once at {@link #start} via {@code usageTracker}, so
     * completed detection results are announced as live updates.
     *
     * @param liveUpdatePublisherPort nullable, following the same convention as {@code
     *                                 overlayPort}/{@code detectionEventRepositoryPort}: {@code
     *                                 null} means no live-update announcements from any stream this
     *                                 service starts.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker,
                                 OverlayPort overlayPort, DetectionEventRepositoryPort detectionEventRepositoryPort,
                                 LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, overlayPort, detectionEventRepositoryPort, liveUpdatePublisherPort,
                SupervisedPublisher.INITIAL_BACKOFF_NANOS, SupervisedPublisher.MAX_BACKOFF_NANOS);
    }

    /**
     * Test seam: same as the 10-argument constructor, with explicit (typically much smaller)
     * source reopen backoff bounds so supervision-related tests don't have to wait out a real
     * 1s-30s backoff. Production always uses the 10-argument constructor's defaults
     * ({@link SupervisedPublisher#INITIAL_BACKOFF_NANOS}/{@link SupervisedPublisher#MAX_BACKOFF_NANOS}).
     */
    DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          OverlayPort overlayPort, DetectionEventRepositoryPort detectionEventRepositoryPort,
                          LiveUpdatePublisherPort liveUpdatePublisherPort,
                          long sourceInitialBackoffNanos, long sourceMaxBackoffNanos) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.videoSourceRegistry = Objects.requireNonNull(videoSourceRegistry, "videoSourceRegistry must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.usageTracker = usageTracker; // nullable: no-op usage tracking when absent
        this.overlayPort = overlayPort; // nullable: no overlay rendering when absent
        this.detectionEventRepositoryPort = detectionEventRepositoryPort; // nullable: no event tracking when absent
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.sourceInitialBackoffNanos = sourceInitialBackoffNanos;
        this.sourceMaxBackoffNanos = sourceMaxBackoffNanos;
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
            // docs/MVP2-PLAN.md §S, S-a: never hand the adapter's own open() result straight to the
            // pipeline -- wrap it so a source I/O error/completion is retried with backoff instead
            // of ending the stream. See SupervisedPublisher's own javadoc and this class's javadoc
            // for exactly what does/doesn't get re-invoked across a reconnect.
            SupervisedPublisher<VideoFrame> supervisedSource = new SupervisedPublisher<>(
                    () -> source.open(streamId, device.stream()),
                    cause -> eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                            "video source disconnected, reconnecting" + describeCauseSuffix(cause))),
                    retryScheduler, sourceInitialBackoffNanos, sourceMaxBackoffNanos);
            DetectionEventEngine eventEngine = detectionEventRepositoryPort == null ? null
                    : new DetectionEventEngine(streamId, deviceId, config.eventRule(), usageTracker,
                            detectionEventRepositoryPort);
            // docs/REALTIME-PLAN.md §4: resolved once, here, rather than re-resolved per completed
            // detection result -- a device's owning asset does not change while its stream runs.
            // Skipped entirely (not just discarded) when there's no LiveUpdatePublisherPort to hand
            // the result to, so a lookup nobody will ever read is never even attempted.
            AssetId ownerAssetId = (usageTracker == null || liveUpdatePublisherPort == null) ? null
                    : usageTracker.resolveAsset(deviceId).orElse(null);
            StreamPipeline pipeline = new StreamPipeline(streamId, device, config, supervisedSource, detectionPort,
                    streamPublisherPort, detectionRepositoryPort, eventPublisher, overlayPort, eventEngine,
                    ownerAssetId, liveUpdatePublisherPort);
            activeStreams.put(streamId, new RunningStream(deviceId, source, supervisedSource, pipeline, Instant.now()));
            pipeline.start();
            eventPublisher.publish(Event.of(streamId, EventType.STREAM_STARTED,
                    "Stream started for device " + device.name()));
            if (usageTracker != null) {
                usageTracker.onStreamStarted(deviceId, streamId);
            }
            return streamId;
        } catch (RuntimeException e) {
            activeStreams.remove(streamId);
            streamByDevice.remove(deviceId, streamId);
            throw e;
        }
    }

    /**
     * Stops a stream: the only path that ever ends one (docs/MVP2-PLAN.md §S, S-a — a source
     * failure alone never does, see {@link #start}). Returns promptly: the stream is removed from
     * every listing and {@link SupervisedPublisher#stop() supervision is cancelled} synchronously
     * before this method returns, but the actual pipeline/source teardown — which some adapters'
     * {@code close()} can block on for a long time (e.g. joining a native capture thread; see
     * {@code adapter-rtsp}'s {@code FfmpegVideoSource}, up to 20s) — runs on a background thread
     * instead of the caller's. This is a deliberate fix for a real bug: that teardown used to run
     * synchronously right here, so a slow adapter blocked whatever thread called this method (a
     * Spring MVC request thread for {@code DELETE /api/streams/{id}}) for as long as it took,
     * which — combined with the browser's small per-origin connection limit queuing every other
     * poll request behind the stuck one — was the actual root cause of the reported "/live stop
     * freezes the app" bug, not anything in the frontend.
     */
    @Override
    public void stop(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.remove(streamId);
        if (active == null) {
            return; // unknown or already-stopped stream: no-op, per the interface contract
        }
        streamByDevice.remove(active.deviceId(), streamId);
        active.supervisedSource().stop(); // fast, in-memory: no further reopen attempt is ever made
        eventPublisher.publish(Event.of(streamId, EventType.STREAM_STOPPED, "Stream stopped"));
        if (usageTracker != null) {
            usageTracker.onStreamStopped(active.deviceId());
        }
        teardownAsync(active.pipeline(), active.source(), streamId);
    }

    /**
     * Releases the pipeline's subscription ({@link StreamPipeline#close()}, which also signals
     * {@link com.drones.vision.domain.port.out.StreamPublisherPort#streamEnded}) and the underlying
     * source ({@link VideoSourcePort#close}) off the calling thread — see {@link #stop}'s javadoc
     * for why. A fire-and-forget virtual thread, the same idiom {@link DefaultDiscoveryService}
     * already uses for its own scan calls: cheap, effectively daemon (a virtual thread never blocks
     * JVM exit), never tracked or interrupted — there is nothing further to do with it once
     * started, and both calls are already idempotent/best-effort by their own contracts.
     */
    private static void teardownAsync(StreamPipeline pipeline, VideoSourcePort source, StreamId streamId) {
        Thread.ofVirtual().name("stream-teardown-" + streamId.value()).start(() -> {
            pipeline.close();
            source.close(streamId);
        });
    }

    private static String describeCauseSuffix(Throwable cause) {
        return cause == null ? "" : ": " + cause.getMessage();
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

    @Override
    public Optional<VideoFrame> latestFrame(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : active.pipeline().latestFrame();
    }

    /** What this service holds per running stream; distinct from the {@link ActiveStream} read model. */
    private record RunningStream(DeviceId deviceId, VideoSourcePort source, SupervisedPublisher<VideoFrame> supervisedSource,
                                  StreamPipeline pipeline, Instant startedAt) {
    }
}
