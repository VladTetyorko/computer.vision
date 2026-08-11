package com.drones.vision.perception.application.stream;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.events.domain.model.Event;
import com.drones.vision.events.domain.model.EventType;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.flight.domain.model.Telemetry;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.events.domain.port.DetectionEventRepositoryPort;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.events.domain.port.DetectionRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import com.drones.vision.events.domain.port.EventPublisherPort;
import com.drones.vision.events.domain.port.LiveUpdatePublisherPort;
import com.drones.vision.perception.domain.port.OverlayPort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.perception.domain.port.VideoSourcePort;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import com.drones.vision.perception.application.pipeline.DetectionEventEngine;
import com.drones.vision.perception.application.pipeline.StreamPipeline;
import com.drones.vision.perception.application.pipeline.StreamPipelineSettings;
import com.drones.vision.perception.application.pipeline.SupervisedPublisher;
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
    private final StreamPipelineSettings settings;

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
     * Same as the 7-argument constructor, plus an {@link OverlayPort} collaborator threaded into
     * every {@link StreamPipeline} this service starts (docs/plans/done/MVP1-PLAN.md §C8 bullet 2). When both
     * this and {@code usageTracker} are present, {@link #start} also builds and threads a telemetry
     * supplier (see that method's own comments) so {@link
     * com.drones.vision.perception.domain.model.PipelineConfig#overlayTelemetry()}'s OSD gate becomes
     * reachable, closing the gap adapter-overlay/MODULE.md documented ("OSD gate not reachable" —
     * {@code StreamPipeline} had no telemetry input at all).
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
     * (docs/plans/done/MVP2-PLAN.md §E, E-a): when present, every {@link StreamPipeline} this service starts
     * is given a fresh, per-stream {@link DetectionEventEngine} built from {@code config}'s {@link
     * com.drones.vision.perception.domain.model.PipelineConfig#eventRule()}, {@code usageTracker} (for
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
     * (docs/plans/done/REALTIME-PLAN.md §4): threaded into every {@link StreamPipeline} this service starts,
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
                StreamPipelineSettings.defaults());
    }

    /**
     * Test seam: same as the 10-argument constructor, with explicit (typically much smaller)
     * source reopen backoff bounds so supervision-related tests don't have to wait out a real
     * 1s-30s backoff, folded onto {@link StreamPipelineSettings#defaults()}'s other tuning.
     * Production always uses the 10-argument constructor's {@link StreamPipelineSettings#defaults()}.
     */
    DefaultStreamService(DeviceRepositoryPort deviceRepository, VideoSourceRegistry videoSourceRegistry,
                          DetectionPort detectionPort, StreamPublisherPort streamPublisherPort,
                          DetectionRepositoryPort detectionRepositoryPort,
                          EventPublisherPort eventPublisher, UsageTracker usageTracker,
                          OverlayPort overlayPort, DetectionEventRepositoryPort detectionEventRepositoryPort,
                          LiveUpdatePublisherPort liveUpdatePublisherPort,
                          long sourceInitialBackoffNanos, long sourceMaxBackoffNanos) {
        this(deviceRepository, videoSourceRegistry, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, usageTracker, overlayPort, detectionEventRepositoryPort, liveUpdatePublisherPort,
                withSourceReopenBackoff(StreamPipelineSettings.defaults(), sourceInitialBackoffNanos,
                        sourceMaxBackoffNanos));
    }

    /**
     * Wiring/test seam: same as the 10-argument constructor, plus an explicit {@link
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
                          OverlayPort overlayPort, DetectionEventRepositoryPort detectionEventRepositoryPort,
                          LiveUpdatePublisherPort liveUpdatePublisherPort,
                          StreamPipelineSettings settings) {
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
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
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
            VideoSourcePort source = videoSourceRegistry.sourceFor(device.stream());
            // docs/plans/done/MVP2-PLAN.md §S, S-a: never hand the adapter's own open() result straight to the
            // pipeline -- wrap it so a source I/O error/completion is retried with backoff instead
            // of ending the stream. See SupervisedPublisher's own javadoc and this class's javadoc
            // for exactly what does/doesn't get re-invoked across a reconnect.
            SupervisedPublisher<VideoFrame> supervisedSource = new SupervisedPublisher<>(
                    () -> source.open(streamId, device.stream()),
                    cause -> eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                            "video source disconnected, reconnecting" + describeCauseSuffix(cause))),
                    retryScheduler, settings.sourceReopenBackoffInitialNanos(),
                    settings.sourceReopenBackoffMaxNanos());
            DetectionEventEngine eventEngine = detectionEventRepositoryPort == null ? null
                    : new DetectionEventEngine(streamId, deviceId, config.eventRule(), usageTracker,
                            detectionEventRepositoryPort);
            // docs/plans/done/REALTIME-PLAN.md §4 / telemetry-OSD input: resolved once, here, rather than
            // re-resolved per completed detection result / per published frame -- a device's owning
            // asset does not change while its stream runs. Skipped entirely (not just discarded)
            // unless something could actually read the result -- a configured LiveUpdatePublisherPort
            // (announces every completed result), or a configured OverlayPort (may need it to build
            // the telemetry supplier below) -- so a lookup nobody will ever read is never even
            // attempted.
            AssetId ownerAssetId = (usageTracker == null || (liveUpdatePublisherPort == null && overlayPort == null))
                    ? null : usageTracker.resolveAsset(deviceId).orElse(null);
            // Telemetry-OSD input (closes the "OSD gate not reachable" gap, see
            // adapter-overlay/MODULE.md): only built when StreamPipeline could ever actually read it
            // -- an OverlayPort to render through and a resolved owning asset to read telemetry for.
            // StreamPipeline itself further gates on PipelineConfig#overlayTelemetry() per call.
            Supplier<Telemetry> telemetrySupplier = (usageTracker != null && overlayPort != null && ownerAssetId != null)
                    ? () -> usageTracker.latestTelemetry(ownerAssetId).orElse(null)
                    : null;
            StreamPipeline pipeline = new StreamPipeline(streamId, device, config, supervisedSource, detectionPort,
                    streamPublisherPort, detectionRepositoryPort, eventPublisher, overlayPort, eventEngine,
                    ownerAssetId, liveUpdatePublisherPort, telemetrySupplier, System::nanoTime, settings);
            activeStreams.put(streamId,
                    new RunningStream(deviceId, source, supervisedSource, pipeline, Instant.now(), lockSeq));
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
     * {@link com.drones.vision.perception.domain.port.StreamPublisherPort#streamEnded}) and the underlying
     * source ({@link VideoSourcePort#close}) off the calling thread — see {@link #stop}'s javadoc
     * for why. A fire-and-forget virtual thread, the same idiom {@link com.drones.vision.warehouse.application.discovery.DefaultDiscoveryService}
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
     * every non-PATCH-able field — {@code maxInFlightInferences}, {@code overlayTelemetry}, {@code
     * eventRule}, {@code overlayBurnIn}, and the model's own {@code version}, frozen contract
     * &sect;3) exactly as {@code current} has it.
     *
     * <p>The tracking component folds <b>per field</b> through {@link TrackingConfigPatch#foldOnto}
     * (docs/plans/done/TRACKING-PLAN.md &sect;4.D): an absent {@code tracking} leaves it entirely alone, and a
     * present one changes only the knobs it names — which is why adjusting the verify cadence and
     * then the follow fps keeps both, and why neither ever drops the operator's target. A lock in
     * the patch has its {@code lockSeq} stamped from this stream's own {@link AtomicLong}, lazily:
     * cv-service applies a restated lock only when its sequence exceeds the last it applied, so a
     * client that could choose the number could replay an abandoned target back into existence.
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
        boolean detectionEnabled =
                patch.detectionEnabled() == null ? current.detectionEnabled() : patch.detectionEnabled();
        TrackingConfig tracking = patch.tracking() == null
                ? current.tracking()
                : patch.tracking().foldOnto(current.tracking(), lockSeq::incrementAndGet);
        return new PipelineConfig(model, confidenceThreshold, inferenceFps, current.maxInFlightInferences(),
                current.overlayTelemetry(), labelFilter, current.eventRule(), current.overlayBurnIn(),
                detectionEnabled, tracking);
    }

    /** {@code config} with its tracking component replaced; {@code config} itself when unchanged. */
    private static PipelineConfig withTracking(PipelineConfig config, TrackingConfig tracking) {
        if (tracking.equals(config.tracking())) {
            return config;
        }
        return new PipelineConfig(config.model(), config.confidenceThreshold(), config.inferenceFps(),
                config.maxInFlightInferences(), config.overlayTelemetry(), config.labelFilter(), config.eventRule(),
                config.overlayBurnIn(), config.detectionEnabled(), tracking);
    }

    /**
     * What this service holds per running stream; distinct from the {@link ActiveStream} read model.
     *
     * @param lockSeq this stream's monotonic target-lock sequence (docs/plans/done/TRACKING-PLAN.md &sect;4.D).
     *                Per-stream, not global: two streams' locks are unrelated, and a shared counter
     *                would make one operator's click advance another's sequence. Starts at 0 and is
     *                only ever incremented, so it never decreases within a stream's life; a stream
     *                restart starts a fresh pipeline (and a fresh cv-service session) at 0 again.
     *                Created by {@link #start} before the config is composed, since a start request
     *                could in principle carry a lock and would then need the first number.
     */
    private record RunningStream(DeviceId deviceId, VideoSourcePort source, SupervisedPublisher<VideoFrame> supervisedSource,
                                  StreamPipeline pipeline, Instant startedAt, AtomicLong lockSeq) {
    }
}
