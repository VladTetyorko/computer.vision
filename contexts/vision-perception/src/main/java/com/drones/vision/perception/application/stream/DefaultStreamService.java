package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.StopReason;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionDemandPort;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.PulledDetectionPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.perception.domain.port.VideoSourcePort;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.drones.vision.warehouse.application.discovery.DefaultDiscoveryService;
import com.drones.vision.perception.application.pipeline.DetectionEventEngine;
import com.drones.vision.perception.application.pipeline.PullDetectionBinding;
import com.drones.vision.perception.application.pipeline.PullDetectionSettings;
import com.drones.vision.perception.application.pipeline.StreamPipeline;
import com.drones.vision.perception.application.pipeline.StreamPipelineSettings;
import com.drones.vision.perception.application.pipeline.SupervisedPublisher;
import com.drones.vision.perception.application.pipeline.DetectionRate;
import com.drones.vision.perception.application.pipeline.PipelineLatency;
import com.drones.vision.perception.application.pipeline.TrackingStats;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.perception.application.pipeline.VideoSourceRegistry;

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
 * <h2>Source supervision (docs/plans/done/MVP2-PLAN.md &sect;S, S-a)</h2>
 * {@link #start} never hands the source adapter's own {@link VideoSourcePort#open} result straight
 * to {@link StreamPipeline}; it wraps it in a {@link SupervisedPublisher} first. A started stream
 * therefore survives a source I/O error or unexpected completion on its own — {@link
 * StreamPipeline} itself is completely unaware this is happening (it only ever sees the normal
 * {@code onSubscribe}/{@code onNext} traffic the wrapper forwards), so its own detection-outage
 * machinery, {@code latestDetections()}, and the {@link com.drones.vision.perception.domain.port.StreamPublisherPort}
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

    private static final System.Logger LOG = System.getLogger(DefaultStreamService.class.getName());

    private final DeviceRepositoryPort deviceRepository;
    private final VideoSourceRegistry videoSourceRegistry;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final UsageTracker usageTracker;
    private final DetectionEventRepositoryPort detectionEventRepositoryPort;
    private final DetectionLiveUpdatePort liveUpdatePublisherPort;
    private final StreamPipelineSettings settings;

    /**
     * Deployment-wide pull-mode wiring (docs/plans/active/MEDIA-SOT-PLAN.md wave M5, switch B) — {@code null}
     * (every constructor but the 12-argument one) means every stream this service starts uses push
     * detection, exactly as before this capability existed. Non-null switches every stream this
     * service starts to the pull-mode detection driver (D12's own note: B is one deployment-wide
     * switch, not per-device — only proxying video, switch A via {@link StreamPublisherPort#proxiesSource},
     * is decided per device).
     */
    private final PullDetectionSettings pullDetectionSettings;

    /**
     * Detection-demand evaluator (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3) — {@code null} means this
     * service never schedules the demand-poll task at all, so no stream it starts is ever gated on
     * anything but {@link PipelineConfig#detectionEnabled()}; {@link StreamPipeline}'s own {@code
     * detectionDemand} field simply stays at its fail-open {@code true} default forever. Non-null
     * schedules {@link #pollDetectionDemand} on {@link #retryScheduler} at {@link
     * StreamPipelineSettings#detectionDemandPollInterval()} once, in the constructor — not per
     * stream — since one tick already iterates every running stream (see that method's own javadoc).
     */
    private final DetectionDemandPort detectionDemandPort;

    /**
     * A video publisher that never emits (D4: when {@link StreamPublisherPort#proxiesSource} is
     * {@code true}, this service does not open a {@link VideoSourcePort} at all). Handed to {@link
     * StreamPipeline} in place of a real source so its video-path half ({@code onNext}, {@code
     * latestFrame}) simply never runs; {@code streamStarted}/{@code streamEnded} still fire (that is
     * what lets a proxying publisher create/delete its mediamtx path), and detection results still
     * flow in over the pull driver when one is wired.
     */
    private static final Flow.Publisher<VideoFrame> NO_VIDEO_SOURCE = subscriber ->
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // no-op: a proxied source never has a frame for this JVM to produce
                }

                @Override
                public void cancel() {
                    // no-op
                }
            });

    /**
     * One dedicated daemon thread scheduling every stream's supervised-reopen retries
     * (docs/plans/done/MVP2-PLAN.md §S, S-a). Shared, not per-stream: a demo/small-fleet stream count keeps
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
     * Same as the 7-argument constructor, plus a {@link DetectionEventRepositoryPort} collaborator
     * (docs/plans/done/MVP2-PLAN.md §E, E-a): when present, every {@link StreamPipeline} this service starts
     * is given a fresh, per-stream {@link DetectionEventEngine} built from {@code config}'s {@link
     * com.drones.vision.perception.domain.model.PipelineConfig#eventRule()}, {@code usageTracker} (for
     * asset/position resolution), and this port.
     *
     * @param detectionEventRepositoryPort nullable, following the same convention as {@code
     *                                      usageTracker}: {@code null} means no debounced {@code
     *                                      DetectionEvent} tracking on any stream this service
     *                                      starts. Deliberately the only new constructor parameter
     *                                      for this feature rather than duplicating {@code
     *                                      AssetRepositoryPort}/{@code AssetUsageRepositoryPort}
     *                                      here — {@code usageTracker} already holds both and now
     *                                      exposes the two read methods {@link DetectionEventEngine}
     *                                      needs.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker,
                                 DetectionEventRepositoryPort detectionEventRepositoryPort) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, detectionEventRepositoryPort, null);
    }

    /**
     * Same as the 8-argument constructor, plus a {@link DetectionLiveUpdatePort} collaborator
     * (docs/plans/done/REALTIME-PLAN.md §4): threaded into every {@link StreamPipeline} this service starts,
     * alongside the owning asset id resolved once at {@link #start} via {@code usageTracker}, so
     * completed detection results are announced as live updates.
     *
     * @param liveUpdatePublisherPort nullable, following the same convention as {@code
     *                                 detectionEventRepositoryPort}: {@code null} means no
     *                                 live-update announcements from any stream this service starts.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                                 DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                                 DetectionRepositoryPort detectionRepositoryPort,
                                 EventPublisherPort eventPublisher, UsageTracker usageTracker,
                                 DetectionEventRepositoryPort detectionEventRepositoryPort,
                                 DetectionLiveUpdatePort liveUpdatePublisherPort) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, detectionEventRepositoryPort, liveUpdatePublisherPort,
                StreamPipelineSettings.defaults());
    }

    /**
     * Test seam: same as the 9-argument constructor, with explicit (typically much smaller)
     * source reopen backoff bounds so supervision-related tests don't have to wait out a real
     * 1s-30s backoff, folded onto {@link StreamPipelineSettings#defaults()}'s other tuning.
     * Production always uses the 9-argument constructor's {@link StreamPipelineSettings#defaults()}.
     */
    DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          DetectionEventRepositoryPort detectionEventRepositoryPort,
                          DetectionLiveUpdatePort liveUpdatePublisherPort,
                          long sourceInitialBackoffNanos, long sourceMaxBackoffNanos) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, detectionEventRepositoryPort, liveUpdatePublisherPort,
                withSourceReopenBackoff(StreamPipelineSettings.defaults(), sourceInitialBackoffNanos,
                        sourceMaxBackoffNanos));
    }

    /**
     * Wiring/test seam: same as the 9-argument constructor, plus an explicit {@link
     * StreamPipelineSettings} (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config extraction) —
     * {@code vision-app} supplies a {@code vision.application.pipeline.*}-bound settings record
     * here instead of this class hardcoding one, and it is threaded into both this service's own
     * {@link SupervisedPublisher} source-reopen backoff and every {@link StreamPipeline} it starts.
     * Public — unlike the other test seams here — because {@code vision-app}'s wiring calls this
     * constructor directly with its own {@code VisionApplicationProperties}-bound settings.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          DetectionEventRepositoryPort detectionEventRepositoryPort,
                          DetectionLiveUpdatePort liveUpdatePublisherPort,
                          StreamPipelineSettings settings) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, detectionEventRepositoryPort, liveUpdatePublisherPort,
                settings, null);
    }

    /**
     * Same as the 10-argument constructor, plus deployment-wide pull-mode wiring (docs/plans/active/MEDIA-SOT-PLAN.md
     * wave M5, switch B) threaded into every {@link StreamPipeline} this service starts.
     *
     * @param pullDetectionSettings nullable, following the same convention as {@code
     *                              liveUpdatePublisherPort}: {@code null} (the 10-argument
     *                              constructor's default) means every stream this service starts uses
     *                              push detection, exactly as before this capability existed.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          DetectionEventRepositoryPort detectionEventRepositoryPort,
                          DetectionLiveUpdatePort liveUpdatePublisherPort,
                          StreamPipelineSettings settings, PullDetectionSettings pullDetectionSettings) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, detectionEventRepositoryPort, liveUpdatePublisherPort,
                settings, pullDetectionSettings, null);
    }

    /**
     * Same as the 11-argument constructor, plus a {@link DetectionDemandPort} collaborator
     * (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3): when present, this constructor schedules {@link
     * #pollDetectionDemand} on {@link #retryScheduler} at {@link
     * StreamPipelineSettings#detectionDemandPollInterval()}, re-evaluating every running stream's
     * demand on each tick.
     *
     * @param detectionDemandPort nullable, following the same convention as {@code
     *                             pullDetectionSettings}: {@code null} (the 11-argument
     *                             constructor's default) means the demand-poll task is never
     *                             scheduled at all, so every stream this service starts is fail-open
     *                             on demand — gated on {@code detectionEnabled} alone, exactly as
     *                             before this capability existed.
     */
    public DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          DetectionEventRepositoryPort detectionEventRepositoryPort,
                          DetectionLiveUpdatePort liveUpdatePublisherPort,
                          StreamPipelineSettings settings, PullDetectionSettings pullDetectionSettings,
                          DetectionDemandPort detectionDemandPort) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
        this.videoSourceRegistry = Objects.requireNonNull(videoSourceRegistry, "videoSourceRegistry must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.usageTracker = usageTracker; // nullable: no-op usage tracking when absent
        this.detectionEventRepositoryPort = detectionEventRepositoryPort; // nullable: no event tracking when absent
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.pullDetectionSettings = pullDetectionSettings; // nullable: every stream uses push detection when absent
        this.detectionDemandPort = detectionDemandPort; // nullable: demand-poll task never scheduled when absent
        if (detectionDemandPort != null) {
            long intervalNanos = settings.detectionDemandPollInterval().toNanos();
            retryScheduler.scheduleAtFixedRate(this::pollDetectionDemand, intervalNanos, intervalNanos,
                    TimeUnit.NANOSECONDS);
        }
    }

    /** Copies {@code base} with its source-reopen backoff bounds replaced. */
    private static StreamPipelineSettings withSourceReopenBackoff(StreamPipelineSettings base, long initialNanos,
                                                                   long maxNanos) {
        return new StreamPipelineSettings(base.assumedSourceFps(), base.measuredFpsEwmaAlpha(), base.warmupFrames(),
                base.minMeasuredFps(), base.maxMeasuredFps(), base.detectionBackoffInitialNanos(),
                base.detectionBackoffMaxNanos(), initialNanos, maxNanos, base.extrapolationMaxMillis(),
                base.extrapolationMatchGate(), base.trackingStatsWindow(), base.trackRetention(),
                base.trackingSeed());
    }

    @Override
    public StreamId start(DeviceId deviceId, PipelineConfig config) {
        return start(deviceId, config, TrackingConfigPatch.NOTHING);
    }

    /**
     * Starts a stream, composing its tracking configuration from all three layers of
     * docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1 in one place — {@code requested} over the deployment
     * seed ({@link StreamPipelineSettings#trackingSeed()}) over {@code config}'s own {@link
     * PipelineConfig#tracking()}, which is the domain's code default on every call site today. Doing
     * it here rather than at each REST edge is what makes the device, asset, simulation and
     * demo-fleet start paths seed identically.
     */
    @Override
    public StreamId start(DeviceId deviceId, PipelineConfig requestedConfig, TrackingConfigPatch requestedTracking) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(requestedConfig, "config must not be null");
        Objects.requireNonNull(requestedTracking, "tracking must not be null");

        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new NoSuchElementException("Unknown device: " + deviceId.value()));
        if (!device.isActive()) {
            throw new IllegalStateException("Device is not in service: " + device.name());
        }

        StreamId streamId = StreamId.random();
        if (streamByDevice.putIfAbsent(deviceId, streamId) != null) {
            throw new IllegalStateException("Device already has an active stream: " + deviceId.value());
        }

        AtomicLong lockSeq = new AtomicLong();
        PipelineConfig config = withTracking(requestedConfig,
                requestedTracking.foldOnto(settings.trackingSeed().foldOnto(requestedConfig.tracking(),
                        lockSeq::incrementAndGet), lockSeq::incrementAndGet));

        try {
            // docs/plans/active/MEDIA-SOT-PLAN.md D4: when the active publisher itself dials this device's
            // source (a proxied RTSP path), this service opens no VideoSourcePort at all -- NO_VIDEO_SOURCE
            // stands in so StreamPipeline's video-path half (onNext, latestFrame) simply never runs, while
            // streamStarted/streamEnded still fire (that is what lets the proxying publisher create/delete
            // its mediamtx path).
            boolean proxied = streamPublisherPort.proxiesSource(device);
            VideoSourcePort source = null;
            SupervisedPublisher<VideoFrame> supervisedSource = null;
            Flow.Publisher<VideoFrame> videoPublisher = NO_VIDEO_SOURCE;
            if (!proxied) {
                source = videoSourceRegistry.sourceFor(device.stream());
                VideoSourcePort openSource = source;
                // docs/plans/done/MVP2-PLAN.md §S, S-a: never hand the adapter's own open() result straight to the
                // pipeline -- wrap it so a source I/O error/completion is retried with backoff instead
                // of ending the stream. See SupervisedPublisher's own javadoc and this class's javadoc
                // for exactly what does/doesn't get re-invoked across a reconnect.
                supervisedSource = new SupervisedPublisher<>(
                        () -> openSource.open(streamId, device.stream()),
                        cause -> eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                                "video source disconnected, reconnecting" + describeCauseSuffix(cause))),
                        retryScheduler, settings.sourceReopenBackoffInitialNanos(),
                        settings.sourceReopenBackoffMaxNanos());
                videoPublisher = supervisedSource;
            }

            // docs/plans/active/MEDIA-SOT-PLAN.md wave M5, D5: switch B (vision.cv.frame-transport) is one
            // deployment-wide choice, not a per-device one -- every stream this service starts either
            // pulls or pushes, decided once by whether pullDetectionSettings was ever wired in.
            PulledDetectionPort pulledDetectionPort = null;
            SupervisedPublisher<DetectionResult> supervisedPulledResults = null;
            PullDetectionBinding pullDetection = null;
            if (pullDetectionSettings != null) {
                pulledDetectionPort = pullDetectionSettings.port();
                PulledDetectionPort openPort = pulledDetectionPort;
                // D2: the mediamtx path name is exactly streamId.value() -- the same convention the
                // publish side already relies on, so the worker dials the identical path this stream's
                // video (if any) is published to.
                URI pullSourceUrl = URI.create(pullDetectionSettings.rtspBase() + "/" + streamId.value());
                supervisedPulledResults = new SupervisedPublisher<>(
                        () -> openPort.open(streamId, pullSourceUrl, config),
                        cause -> eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                                "pulled detection disconnected, reconnecting" + describeCauseSuffix(cause))),
                        retryScheduler, settings.sourceReopenBackoffInitialNanos(),
                        settings.sourceReopenBackoffMaxNanos());
                pullDetection =
                        new PullDetectionBinding(pulledDetectionPort, supervisedPulledResults,
                                pullDetectionSettings.wallClock());
            }

            DetectionEventEngine eventEngine = detectionEventRepositoryPort == null ? null
                    : new DetectionEventEngine(streamId, deviceId, config.eventRule(), usageTracker,
                            detectionEventRepositoryPort);
            // docs/plans/done/REALTIME-PLAN.md §4 / ego-motion telemetry input: resolved once, here, rather
            // than re-resolved per completed detection result / per published frame -- a device's owning
            // asset does not change while its stream runs. Skipped entirely (not just discarded) unless
            // something could actually read the result -- a configured DetectionLiveUpdatePort (announces
            // every completed result), or a configured field of view (CameraAttitude needs it for
            // ego-motion compensation) -- so a lookup nobody will ever read is never even attempted.
            //
            // INVARIANT (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-1): this supplier must be built
            // unconditionally with respect to overlay -- it once shipped dead because its construction
            // was gated on an OverlayPort being present, which silently meant a deployment with a
            // configured field of view but no overlay sent no CameraPose at all (a defect invisible to
            // any test that injects the supplier directly, caught only by watching the wire). Now that
            // overlay/OverlayPort do not exist at all, the only gate left is attitudeWanted itself --
            // removing OverlayPort must never reintroduce a second, accidental gate here.
            boolean attitudeWanted = settings.cameraHfovDegrees() > 0.0;
            AssetId ownerAssetId = (usageTracker == null || (liveUpdatePublisherPort == null && !attitudeWanted))
                    ? null : usageTracker.resolveAsset(deviceId).orElse(null);
            // Still not built when nothing could read it -- the original cost gate is preserved.
            // StreamPipeline gates the attitude read on the field of view (cameraAttitude()).
            Supplier<Telemetry> telemetrySupplier =
                    (usageTracker != null && ownerAssetId != null && attitudeWanted)
                            ? () -> usageTracker.latestTelemetry(ownerAssetId).orElse(null)
                            : null;
            StreamPipeline pipeline = new StreamPipeline(streamId, device, config, videoPublisher, detectionPort,
                    streamPublisherPort, detectionRepositoryPort, eventPublisher, eventEngine,
                    ownerAssetId, liveUpdatePublisherPort, telemetrySupplier, System::nanoTime, settings,
                    pullDetection);
            // docs/plans/active/CV-DEMAND-PLAN.md §1: seeded to Instant.EPOCH, not Instant.now() -- demand must
            // be observed, never assumed. StreamPipeline#detectionDemand's own fail-open true default
            // already covers a just-started stream until the first poll tick (at most
            // detectionDemandPollInterval, not a full detectionDemandGrace); seeding this to "now" would
            // instead grant every newly started stream a full grace window of assumed demand, exactly
            // backwards for a wave whose point is "many streams started at once must not each burn a
            // grace period of inference for nobody."
            activeStreams.put(streamId, new RunningStream(deviceId, source, supervisedSource, pulledDetectionPort,
                    supervisedPulledResults, pipeline, Instant.now(), lockSeq,
                    new AtomicReference<>(Instant.EPOCH)));
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
     * Stops a stream: the only path that ever ends one (docs/plans/done/MVP2-PLAN.md §S, S-a — a source
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
        stop(streamId, StopReason.OPERATOR);
    }

    @Override
    public void stop(StreamId streamId, StopReason reason) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        RunningStream active = activeStreams.remove(streamId);
        if (active == null) {
            return; // unknown or already-stopped stream: no-op, per the interface contract
        }
        streamByDevice.remove(active.deviceId(), streamId);
        // Proxied streams open no VideoSourcePort (D4), and push-mode streams wire no pulled
        // detection -- both supervised publishers are nullable and independently guarded here.
        if (active.supervisedSource() != null) {
            active.supervisedSource().stop(); // fast, in-memory: no further reopen attempt is ever made
        }
        if (active.supervisedPulledResults() != null) {
            active.supervisedPulledResults().stop();
        }
        eventPublisher.publish(stoppedEvent(streamId, reason));
        if (usageTracker != null) {
            usageTracker.onStreamStopped(active.deviceId());
        }
        teardownAsync(active.pipeline(), active.source(), active.pulledDetectionPort(), streamId);
    }

    /**
     * Releases the pipeline's subscription ({@link StreamPipeline#close()}, which also signals
     * {@link com.drones.vision.perception.domain.port.StreamPublisherPort#streamEnded}), the underlying video
     * source ({@link VideoSourcePort#close}, skipped when {@code source} is {@code null} — a proxied
     * stream opened none, D4), and the pulled detection port ({@link PulledDetectionPort#close},
     * skipped when {@code null} — a push-mode stream wired none) off the calling thread — see {@link
     * #stop}'s javadoc for why. A fire-and-forget virtual thread, the same idiom {@link
     * DefaultDiscoveryService} already uses for its own scan calls: cheap, effectively daemon (a
     * virtual thread never blocks JVM exit), never tracked or interrupted — there is nothing further
     * to do with it once started, and every call here is already idempotent/best-effort by its own
     * contract.
     */
    private static void teardownAsync(StreamPipeline pipeline, VideoSourcePort source,
                                       PulledDetectionPort pulledDetectionPort, StreamId streamId) {
        Thread.ofVirtual().name("stream-teardown-" + streamId.value()).start(() -> {
            pipeline.close();
            if (source != null) {
                source.close(streamId);
            }
            if (pulledDetectionPort != null) {
                pulledDetectionPort.close(streamId);
            }
        });
    }

    /**
     * The {@code STREAM_STOPPED} event, worded and tagged by {@code reason}
     * (docs/plans/active/STREAM-STATE-PLAN.md &sect;3.2). The reason also travels as a structured attribute,
     * not only inside the prose: the message is for a human reading the ticker, the attribute is what
     * a client can branch on without parsing English.
     */
    private static Event stoppedEvent(StreamId streamId, StopReason reason) {
        String message = reason == StopReason.IDLE_NO_VIEWERS
                ? "Stream stopped: no viewers"
                : "Stream stopped";
        return new Event(UUID.randomUUID().toString(), streamId, Instant.now(), EventType.STREAM_STOPPED,
                message, Map.of("reason", reason.name()));
    }

    private static String describeCauseSuffix(Throwable cause) {
        return cause == null ? "" : ": " + cause.getMessage();
    }

    @Override
    public List<ActiveStream> streams() {
        return activeStreams.entrySet().stream()
                .map(e -> new ActiveStream(e.getKey(), e.getValue().deviceId(), e.getValue().startedAt(),
                        stateOf(e.getValue()), e.getValue().pipeline().config().detectionEnabled()))
                .toList();
    }

    @Override
    public Optional<StreamState> streamState(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(stateOf(active));
    }

    @Override
    public Optional<PipelineConfig> config(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(active.pipeline().config());
    }

    /**
     * Joins the three facts {@link StreamState#resolve} needs, which no single collaborator holds:
     * whether a video source was opened here at all (this service's own {@code start} decision),
     * whether its supervisor is mid-outage (the supervisor's), and the frame cadence (the
     * pipeline's).
     *
     * <p><b>A null {@code supervisedSource} is exactly the proxied case</b>
     * (docs/plans/active/MEDIA-SOT-PLAN.md D4) — {@code start} wires one if and only if the active
     * publisher does not dial the device itself — so it is the honest test for observability rather
     * than a defensive null check. Such a stream can only ever be {@link StreamState#UNOBSERVED},
     * and must never be reported as {@code STARTING}: its frame count stays {@code 0} for as long as
     * it runs.
     *
     * <p>Deliberately consults only the <i>video</i> supervisor. In pull mode a second
     * {@code SupervisedPublisher} supervises the detection-result stream; folding its outage in here
     * would report a detector fault as a video fault, which is the axis collapse
     * {@link StreamState}'s javadoc forbids.
     */
    private StreamState stateOf(RunningStream active) {
        SupervisedPublisher<VideoFrame> supervisedSource = active.supervisedSource();
        boolean sourceObservable = supervisedSource != null;
        StreamPipeline pipeline = active.pipeline();
        return StreamState.resolve(sourceObservable, sourceObservable && supervisedSource.reconnecting(),
                pipeline.framesObserved(), pipeline.nanosSinceLastFrame(), settings.videoStaleAfter().toNanos());
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

    @Override
    public Optional<VideoFrame> latestRawFrame(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : active.pipeline().latestRawFrame();
    }

    @Override
    public List<Detection> latestDetections(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? List.of() : active.pipeline().latestDetections();
    }

    @Override
    public List<TrackedObject> tracks(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? List.of() : active.pipeline().tracks();
    }

    @Override
    public Optional<TrackingStats> trackingStats(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(active.pipeline().trackingStats());
    }

    @Override
    public Optional<PipelineLatency> pipelineLatency(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(active.pipeline().pipelineLatency());
    }

    @Override
    public Optional<DetectionRate> detectionRate(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(active.pipeline().detectionRate());
    }

    @Override
    public Optional<DetectionState> detectionState(StreamId streamId) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        RunningStream active = activeStreams.get(streamId);
        return active == null ? Optional.empty() : Optional.of(active.pipeline().detectionState());
    }

    /**
     * The demand-poll task (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3), scheduled on {@link #retryScheduler}
     * at {@link StreamPipelineSettings#detectionDemandPollInterval()} only when {@link
     * #detectionDemandPort} is non-null — see the constructor. Re-evaluates every currently running
     * stream once per tick.
     *
     * <p><b>Each stream's evaluation is individually wrapped in {@code catch (Throwable)}.</b> {@link
     * java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate} silently cancels every
     * future run of a task that ever propagates an exception out of it — a single stream's
     * misbehaving {@link DetectionDemandPort} call (or an unexpected {@code null}, or any other
     * bug) must never be allowed to freeze <em>every</em> stream's demand at whatever it last was,
     * for the rest of the JVM's life, with no further error ever surfacing. A failure here is
     * logged once and that one stream's demand is left exactly where it was until the next tick
     * evaluates it again; every other stream in the same tick is unaffected.
     */
    private void pollDetectionDemand() {
        Instant now = Instant.now();
        for (StreamId streamId : activeStreams.keySet()) {
            try {
                evaluateDetectionDemand(streamId, now);
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.WARNING,
                        "detection-demand evaluation failed for stream " + streamId.value(), t);
            }
        }
    }

    /**
     * Evaluates and applies one stream's detection demand (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3):
     * resolves the stream's owning asset (mirroring {@link #start}'s own {@code ownerAssetId}
     * resolution — {@code null} when {@link #usageTracker} is absent or the device has no owning
     * asset), asks {@link #detectionDemandPort}, stamps {@code lastDemandAt} when wanted, and
     * computes whether the stream is still within {@link
     * StreamPipelineSettings#detectionDemandGrace()} of its last observed demand either way — a
     * stream just stamped is trivially within grace of itself, so this single computation covers
     * both the "wanted now" and "wanted recently" cases without a separate branch.
     *
     * <p>Package-private, taking an explicit {@code now} rather than reading {@link Instant#now()}
     * itself, so the same-package test can drive the grace period deterministically — stamping a
     * known instant, then asking again at a known later instant — instead of waiting on the real
     * scheduler or the system clock. A no-op for an unknown/already-stopped stream id, mirroring
     * every other per-stream read in this class.
     *
     * @param streamId the stream to evaluate
     * @param now      the instant to evaluate demand as of
     */
    void evaluateDetectionDemand(StreamId streamId, Instant now) {
        RunningStream active = activeStreams.get(streamId);
        if (active == null) {
            return;
        }
        AssetId assetId = usageTracker == null ? null : usageTracker.resolveAsset(active.deviceId()).orElse(null);
        boolean wanted = detectionDemandPort.detectionWanted(streamId, assetId);
        if (wanted) {
            active.lastDemandAt().set(now);
        }
        Duration sinceLastDemand = Duration.between(active.lastDemandAt().get(), now);
        boolean effective = sinceLastDemand.compareTo(settings.detectionDemandGrace()) < 0;
        active.pipeline().updateDetectionDemand(effective);
    }

    /**
     * Resolves the running stream, merges {@code patch} onto its {@link
     * StreamPipeline#config() current config}, and swaps it in (docs/plans/done/CV-CONTROL-PLAN.md &sect;5,
     * docs/plans/done/TRACKING-PLAN.md &sect;4.D). The merge — and therefore the merged {@link PipelineConfig}'s
     * own compact-ctor validation — runs <b>before</b> {@link StreamPipeline#updateConfig} is ever
     * called, so an invalid patch value never touches the running pipeline at all.
     *
     * <p>{@code trackingChanged} is decided by comparing the <i>folded</i> {@link TrackingConfig}
     * with the running one, not by the patch merely carrying the object: restating the identical
     * configuration is not a change. A re-issued lock does compare unequal, because the fold stamps
     * it with a freshly allocated {@code lockSeq} — see {@link #foldTracking}.
     */
    @Override
    public UpdateOutcome updateConfig(StreamId streamId, PipelineConfigPatch patch) {
        Objects.requireNonNull(streamId, "streamId must not be null");
        Objects.requireNonNull(patch, "patch must not be null");
        RunningStream active = activeStreams.get(streamId);
        if (active == null) {
            throw new NoSuchElementException("Unknown or not-running stream: " + streamId.value());
        }
        PipelineConfig current = active.pipeline().config();
        boolean modelReArmed = patch.modelId() != null && !patch.modelId().equals(current.model().id());
        PipelineConfig merged = mergeConfig(current, patch, active.lockSeq());
        boolean trackingChanged = patch.tracking() != null && !merged.tracking().equals(current.tracking());
        active.pipeline().updateConfig(merged);
        return new UpdateOutcome(modelReArmed, trackingChanged);
    }

    /**
     * Folds {@code patch}'s present fields onto {@code current}, leaving every absent field (and
     * every non-PATCH-able field — {@code maxInFlightInferences}, {@code eventRule}, and the
     * model's own {@code version}, frozen contract &sect;3) exactly as {@code current} has it.
     *
     * <p>The tracking component folds <b>per field</b> through {@link TrackingConfigPatch#foldOnto}
     * (docs/plans/done/TRACKING-PLAN.md &sect;4.D): an absent {@code tracking} leaves it entirely alone, and a
     * present one changes only the knobs it names — which is why adjusting the verify cadence and
     * then the follow fps keeps both, and why neither ever drops the operator's target. A lock in
     * the patch has its {@code lockSeq} stamped from this stream's own {@link AtomicLong}, lazily:
     * cv-service applies a restated lock only when its sequence exceeds the last it applied, so a
     * client that could choose the number could replay an abandoned target back into existence.
     *
     * <p>{@code labelFilter}/{@code labelDenyFilter} each replace their running set wholesale when
     * present (docs/plans/active/CV-CLEAN-FEED-PLAN.md &sect;2, D-2) — same "absent = unchanged,
     * present = replace" semantics, independent of one another.
     */
    private static PipelineConfig mergeConfig(PipelineConfig current, PipelineConfigPatch patch,
                                               AtomicLong lockSeq) {
        ModelRef model = patch.modelId() == null
                ? current.model()
                : new ModelRef(patch.modelId(), current.model().version());
        double confidenceThreshold =
                patch.confidenceThreshold() == null ? current.confidenceThreshold() : patch.confidenceThreshold();
        int inferenceFps = patch.inferenceFps() == null ? current.inferenceFps() : patch.inferenceFps();
        Set<String> labelFilter = patch.labelFilter() == null ? current.labelFilter() : patch.labelFilter();
        Set<String> labelDenyFilter =
                patch.labelDenyFilter() == null ? current.labelDenyFilter() : patch.labelDenyFilter();
        boolean detectionEnabled =
                patch.detectionEnabled() == null ? current.detectionEnabled() : patch.detectionEnabled();
        TrackingConfig tracking = patch.tracking() == null
                ? current.tracking()
                : patch.tracking().foldOnto(current.tracking(), lockSeq::incrementAndGet);
        return new PipelineConfig(model, confidenceThreshold, inferenceFps, current.maxInFlightInferences(),
                labelFilter, current.eventRule(), detectionEnabled, tracking, labelDenyFilter);
    }

    /** {@code config} with its tracking component replaced; {@code config} itself when unchanged. */
    private static PipelineConfig withTracking(PipelineConfig config, TrackingConfig tracking) {
        if (tracking.equals(config.tracking())) {
            return config;
        }
        return new PipelineConfig(config.model(), config.confidenceThreshold(), config.inferenceFps(),
                config.maxInFlightInferences(), config.labelFilter(), config.eventRule(),
                config.detectionEnabled(), tracking, config.labelDenyFilter());
    }

    /**
     * What this service holds per running stream; distinct from the {@link ActiveStream} read model.
     *
     * @param source                  the video source this stream opened, or {@code null} for a
     *                                 proxied stream that opened none (D4); {@code supervisedSource}
     *                                 mirrors this nullability
     * @param pulledDetectionPort     the pull-mode port this stream opened a pull against, or {@code
     *                                 null} for a push-mode stream; {@code supervisedPulledResults}
     *                                 mirrors this nullability (docs/plans/active/MEDIA-SOT-PLAN.md wave M5)
     * @param lockSeq                 this stream's monotonic target-lock sequence (docs/plans/done/TRACKING-PLAN.md
     *                                 &sect;4.D). Per-stream, not global: two streams' locks are unrelated, and
     *                                 a shared counter would make one operator's click advance
     *                                 another's sequence. Starts at 0 and is only ever incremented, so
     *                                 it never decreases within a stream's life; a stream restart
     *                                 starts a fresh pipeline (and a fresh cv-service session) at 0
     *                                 again. Created by {@link #start} before the config is composed,
     *                                 since a start request could in principle carry a lock and would
     *                                 then need the first number
     * @param lastDemandAt            the instant {@link #evaluateDetectionDemand} last observed real
     *                                 demand for this stream (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3);
     *                                 seeded to {@link Instant#EPOCH} by {@link #start}, deliberately
     *                                 <b>not</b> {@link Instant#now()} &mdash; demand must be
     *                                 <i>observed</i>, never assumed, so a just-started stream with no
     *                                 viewers is not silently granted a full {@link
     *                                 StreamPipelineSettings#detectionDemandGrace()} window (30s) of
     *                                 assumed demand it never earned. The narrower startup window this
     *                                 opens instead is already covered by {@link
     *                                 StreamPipeline#detectionDemand()}'s own fail-open {@code true}
     *                                 default: a just-started stream detects until the <b>first</b>
     *                                 poll tick decides otherwise, at most one {@link
     *                                 StreamPipelineSettings#detectionDemandPollInterval()} (2s), not a
     *                                 full grace period. Grace, once observed demand exists, then means
     *                                 only what the plan says it means &mdash; keep detecting this long
     *                                 <i>after</i> a consumer leaves, never "assume a consumer for this
     *                                 long before any evaluation has happened." An {@link
     *                                 AtomicReference}, not a plain field, because {@code
     *                                 RunningStream} is otherwise immutable and this is its one piece
     *                                 of state that genuinely mutates over a running stream's life,
     *                                 from a different thread (the demand-poll scheduler) than the one
     *                                 that created it
     */
    private record RunningStream(DeviceId deviceId, VideoSourcePort source, SupervisedPublisher<VideoFrame> supervisedSource,
                                  PulledDetectionPort pulledDetectionPort,
                                  SupervisedPublisher<DetectionResult> supervisedPulledResults,
                                  StreamPipeline pipeline, Instant startedAt, AtomicLong lockSeq,
                                  AtomicReference<Instant> lastDemandAt) {
    }
}
