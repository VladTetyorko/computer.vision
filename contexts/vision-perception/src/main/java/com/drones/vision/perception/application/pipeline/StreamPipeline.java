package com.drones.vision.perception.application.pipeline;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.perception.domain.model.CameraAttitude;
import com.drones.vision.perception.domain.model.Detection;
import com.drones.vision.perception.domain.model.DetectionResult;
import com.drones.vision.perception.domain.model.DetectionState;
import com.drones.vision.perception.domain.model.FollowStatus;
import com.drones.vision.perception.domain.model.StreamState;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventType;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.perception.domain.model.PullTelemetry;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.perception.domain.model.TargetLock;
import com.drones.vision.perception.domain.model.TrackedObject;
import com.drones.vision.perception.domain.model.TrackingConfig;
import com.drones.vision.perception.domain.model.TrackingMode;
import com.drones.vision.perception.domain.model.VideoFrame;
import com.drones.vision.perception.domain.port.DetectionPort;
import com.drones.vision.perception.domain.port.DetectionRepositoryPort;
import com.drones.vision.platform.EventPublisherPort;
import com.drones.vision.perception.domain.port.DetectionLiveUpdatePort;
import com.drones.vision.perception.domain.port.StreamPublisherPort;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import com.drones.vision.perception.application.stream.DefaultStreamService;
import com.drones.vision.perception.application.stream.PipelineConfigPatch;
import com.drones.vision.perception.application.stream.StreamService;

/**
 * Per-stream pipeline runtime: subscribes to a video source, publishes every
 * frame, samples a subset of frames for CV inference, and fans out
 * detections/events.
 *
 * <p>One instance exists per running stream (created by {@link
 * StreamService}), consistent with the platform's scalability decision that
 * a stream pipeline is pinned to a single JVM instance and holds no
 * static/global mutable state.
 *
 * <h2>Frame path</h2>
 * <ul>
 *   <li>Subscribes to the source {@link Flow.Publisher} as a {@link
 *       Flow.Subscriber}, requesting exactly one frame at a time ({@code
 *       request(1)}). This pipeline never buffers demand ahead of what it is
 *       currently processing; the source adapter owns the drop policy for a
 *       live feed when this subscriber isn't ready (latest-wins), per {@code
 *       VideoSourcePort}'s contract.</li>
 *   <li>Every frame is forwarded to {@link StreamPublisherPort#publish}
 *       regardless of whether it is sampled for inference, and regardless of
 *       whether a detection outage (see below) is in progress — the video
 *       path never depends on the CV service being healthy.</li>
 *   <li><b>Published video is always clean pixels</b> (docs/plans/done/CV-CLEAN-FEED-PLAN.md
 *       D-1): the frame handed to {@link StreamPublisherPort#publish} is exactly the frame this
 *       source produced — there is no server-side burn-in of detections or a telemetry OSD
 *       anymore. Boxes are data only, fanned out via {@link #latestDetections()}/{@link
 *       #tracks()}/{@link DetectionLiveUpdatePort} — a consuming client (the SPA's own vector
 *       overlay) decides whether and how to render them. This removed the JPEG decode + annotate
 *       + re-encode this pipeline used to pay per published frame per stream, and the double-draw
 *       defect a server-burned box plus a client-drawn one produced together.</li>
 * </ul>
 *
 * <h2>Inference sampling</h2>
 * Frames are sampled against a <b>deadline</b> derived from {@link
 * PipelineConfig#inferenceFps()}: the first frame arriving at or after the
 * next deadline is sampled, and the schedule then advances by one interval
 * ({@link #sampleDue}). Because every deadline is served by exactly one
 * frame, the achieved rate equals the requested rate for any source faster
 * than it — unlike the integer {@code sequence % everyNth} stride this
 * replaced, which could only approximate a target the source rate was not a
 * multiple of (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1).
 *
 * <p>The source's arrival rate is still measured — each frame's
 * inter-arrival delta (from an injectable {@link LongSupplier} nanotime
 * source, defaulting to {@link System#nanoTime()}) folded into an
 * exponentially-weighted moving average — but it now <i>reports</i> rather
 * than <i>drives</i>: it is the ceiling no sample rate can exceed, and the
 * evidence behind a {@link DetectionRate#missedDeadlines()} count. Until
 * {@link StreamPipelineSettings#warmupFrames()} frames have arrived the
 * measurement is not trusted and the configured assumption is reported
 * instead; the measured rate is sanity-clamped to guard against a
 * stalled/degenerate clock.
 *
 * <p>A sampled frame is only submitted to {@link DetectionPort#detect} if
 * fewer than {@link PipelineConfig#maxInFlightInferences()} calls are
 * currently in flight; otherwise it is skipped — never queued — so a slow CV
 * service can never stall the video path. Each of those three outcomes is
 * counted in {@link #detectionRate()}, so a stream that does not achieve its
 * configured rate names the reason rather than leaving it to be inferred.
 *
 * <p>The most recently completed detection result is kept in a {@code
 * volatile} field ({@link #latestDetections()}); it is persisted via {@link
 * DetectionRepositoryPort} and announced via a {@code DETECTION} {@link
 * Event} only when non-empty, so uneventful frames don't spam storage/events.
 *
 * <p>The most recently <b>published</b> frame is likewise kept in a {@code volatile} field ({@link
 * #latestFrame()}, docs/plans/done/MVP3-PLAN.md C-a) — the same instance {@link StreamPublisherPort#publish}
 * was just handed, exactly the source's own pixels (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1 —
 * there is no server-side rendering stage anymore), a latest-wins reference swap with no
 * per-frame copy. This is what backs the manager dashboard's per-stream JPEG snapshot endpoint and
 * training-sample capture alike, since a clean published frame is exactly what both want.
 *
 * <p><b>Debounced detection events</b> (docs/plans/done/MVP2-PLAN.md §E, E-a): every completed result —
 * empty or not — also feeds an optional {@link DetectionEventEngine} ({@code eventEngine},
 * nullable, same convention as {@code usageTracker}), which collapses a tracked label's
 * consecutive-qualifying-results streak into an open/close {@code DetectionEvent} lifecycle. This
 * is a separate concern from {@link #latestDetections()}: the engine only ever reads results, it
 * never influences what gets published or returned from this class.
 *
 * <p><b>Tracking</b> (docs/plans/done/TRACKING-PLAN.md &sect;5.D/&sect;5.E): three further consumers on that same
 * fan-out. {@link TrackBook} keeps this stream's tracks by id with their lifetimes ({@link
 * #tracks()}); {@link TrackingStatsWindow} keeps rolling duty-cycle counters over the {@link
 * com.drones.vision.perception.domain.model.TrackingTelemetry} riding each result ({@link #trackingStats()});
 * {@link FollowTracker} (docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1/W2) turns the same telemetry's
 * {@code lockedTrackId} bounces into an honest {@link FollowStatus} lifecycle for whichever {@code
 * FOLLOW} lock is currently held ({@link #followStatus()}). All three are cleared on a model re-arm,
 * exactly as {@link #extrapolator} is. Tracking also reaches the sampling logic above through one
 * value: {@link #effectiveInferenceFps()}, which raises the sample rate to {@code followFps} while
 * the stream is in {@link TrackingMode#FOLLOW}. Nothing else in this class knows tracking exists —
 * no branch in the publish path, none in {@link #maybeDetect}.
 *
 * <h2>Error handling &amp; lifecycle</h2>
 * Two failure classes are handled very differently, on purpose: a CV service
 * outage must never take the video path down with it.
 * <ul>
 *   <li><b>Source errors</b> ({@link #onError}) and any {@code
 *       RuntimeException} thrown synchronously while publishing a frame are
 *       fatal: one {@code PIPELINE_ERROR} event is published and the
 *       pipeline stops cleanly (cancels the subscription, signals {@link
 *       StreamPublisherPort#streamEnded}).</li>
 *   <li><b>Detection failures</b> (an exceptionally-completed {@link
 *       java.util.concurrent.CompletionStage} from {@link
 *       DetectionPort#detect}, including timeouts) never close the pipeline
 *       and never touch the video path. The first failure enters a
 *       detection <i>outage</i>: exactly one {@code PIPELINE_ERROR} event is
 *       published (naming the cause) and, until recovery, sampled frames are
 *       withheld from {@link #detectionPort} entirely — they are skipped
 *       just like the in-flight bound skips frames, and likewise never
 *       queued — while an exponential backoff (starting at {@link
 *       #INITIAL_BACKOFF_NANOS}, doubling, capped at {@link
 *       #MAX_BACKOFF_NANOS}) counts down. Once the backoff deadline
 *       passes, exactly one probe inference is attempted (never more than
 *       one concurrently, even if several sampled frames land inside the
 *       same window): success ends the outage (logged via {@link
 *       System.Logger}, no additional event) and detection resumes
 *       normally; failure doubles the backoff and the outage continues,
 *       still under the single original event — subsequent failures during
 *       an outage, whether from the probe or from a call that was already
 *       in flight when the outage began, are counted but never raise a
 *       second event. A successful detection, whether or not it followed an
 *       outage, always resets the backoff back to its initial interval. A
 *       later, independent outage raises its own new single event.</li>
 * </ul>
 * {@link #close()} is idempotent and safe to call from any thread, including
 * concurrently with an in-flight {@code onNext}.
 */
public final class StreamPipeline implements Flow.Subscriber<VideoFrame>, AutoCloseable {

    /**
     * The detection-outage backoff bounds from {@link StreamPipelineSettings#defaults()}, exposed
     * here purely so same-package tests can assert against them by name (docs/plans/active/LAYERING-REFACTOR-PLAN.md
     * &sect;1.3 config extraction) — this pipeline's own working backoff state ({@link
     * #backoffNanos}) always reads from the {@link StreamPipelineSettings} actually supplied to its
     * constructor, not from these two constants, so a non-default settings object genuinely takes
     * effect at runtime.
     */
    static final long INITIAL_BACKOFF_NANOS = StreamPipelineSettings.defaults().detectionBackoffInitialNanos();

    /** @see #INITIAL_BACKOFF_NANOS */
    static final long MAX_BACKOFF_NANOS = StreamPipelineSettings.defaults().detectionBackoffMaxNanos();

    private static final System.Logger LOG = System.getLogger(StreamPipeline.class.getName());

    /** Unit conversion, not a tunable — a second in nanoseconds. */
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final StreamId streamId;
    private final Device device;

    /**
     * Live-swappable per docs/plans/done/CV-CONTROL-PLAN.md &sect;A — every per-frame read below (sampling's
     * {@code inferenceFps}, {@link #maybeDetect}'s {@code maxInFlightInferences}/{@code
     * detectionEnabled}, and the {@code config} passed
     * into {@link #detectionPort}{@code .detect}) re-reads this field directly, so a write from
     * {@link #updateConfig} is visible to the very next frame with no lock and no restart. See
     * {@link #updateConfig}'s own javadoc for the model-id re-arm case.
     */
    private volatile PipelineConfig config;

    /**
     * Detection <b>demand</b> (docs/plans/done/CV-DEMAND-PLAN.md &sect;1, &sect;3.2) — the system-derived
     * "someone is actually consuming the output" gate, independent of {@link #config}'s {@link
     * PipelineConfig#detectionEnabled()} operator-intent gate. {@code volatile}, the same live-swap
     * shape as {@link #config} itself: {@link #updateDetectionDemand} writes it from {@code
     * DefaultStreamService}'s demand-poll scheduler thread, {@link #maybeDetect} reads it on the
     * video thread, and a write is visible to the very next frame with no lock and no restart.
     * Initializes to {@code true} — fail-open, so a pipeline whose {@code DefaultStreamService} was
     * never given a {@code DetectionDemandPort} never has this field written at all, and {@link
     * #maybeDetect} gates on {@link PipelineConfig#detectionEnabled()} alone, exactly as before this
     * gate existed.
     */
    private volatile boolean detectionDemand = true;

    /**
     * Guards {@link #gateWasOpen} (docs/plans/done/CV-DEMAND-PLAN.md &sect;5/&sect;7's gate-close-clearing
     * correction): {@link #updateConfig} (operator intent, an HTTP-request thread) and {@link
     * #updateDetectionDemand} (viewer demand, {@code DefaultStreamService}'s demand-poll scheduler
     * thread) can each close the gate, so the read-compare-write in {@link
     * #handleDetectionGateTransition()} needs to be atomic across both — without this lock two closes
     * racing on those threads could each observe the gate as still "open" and both fire the clear, or
     * an interleaved close/reopen could leave {@link #gateWasOpen} out of sync with reality.
     */
    private final Object gateLock = new Object();

    /**
     * Last observed value of {@link #detectionGateOpen()}, read/written only under {@link
     * #gateLock}. Seeded from the constructor's own {@link #config}/{@link #detectionDemand} so the
     * very first genuine open&rarr;closed edge — not construction itself — is what triggers the
     * first clear.
     */
    private boolean gateWasOpen;

    private final Flow.Publisher<VideoFrame> source;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final DetectionEventEngine eventEngine;
    private final AssetId assetId;
    private final DetectionLiveUpdatePort liveUpdatePublisherPort;
    private final Supplier<Telemetry> telemetrySupplier;
    private final LongSupplier nanoTimeSource;
    private final DetectionExtrapolator extrapolator;

    /**
     * Pull-mode detection driver (docs/plans/done/MEDIA-SOT-PLAN.md wave M5, D5/D6) — {@code null} means push
     * mode: {@link #maybeDetect} samples frames and calls {@link #detectionPort} directly, unchanged.
     * Non-null switches this pipeline to {@link PullResultSubscriber}, which subscribes to {@link
     * PullDetectionBinding#results()} and forwards every arriving result whose {@link
     * #detectionGateOpen()} holds to the same {@link #onDetectionResult} fan-out push mode uses — a
     * gated-off result is dropped instead (docs/plans/done/CV-DEMAND-PLAN.md &sect;5) — the seam is
     * here and in {@link #maybeDetect}'s guard, never inside {@link #onDetectionResult} itself.
     */
    private final PullDetectionBinding pullDetection;

    /**
     * Two more consumers on {@link #onDetectionResult}'s existing fan-out, built here rather than
     * injected for exactly the reason {@link #extrapolator} is (docs/plans/done/TRACKING-PLAN.md &sect;5.E,
     * TRACKING-ORCHESTRATION.md &sect;2.3): they are this pipeline's own per-stream bookkeeping, not
     * substitutable collaborators, so they cost this class's constructor nothing. They are peers,
     * not one class — see {@link TrackingStatsWindow}'s javadoc for why the counters do not live on
     * the book.
     */
    private final TrackBook trackBook;

    /** @see #trackBook */
    private final TrackingStatsWindow trackingStats;

    /** @see #trackBook */
    private final FollowTracker followTracker;

    /**
     * Wall-clock cost of the detection round trip, as opposed to the compute cost cv-service
     * self-reports (docs/conclusions/CV-RATE-BUDGET.md §3). Separate from {@link #trackingStats}
     * because it must keep counting when tracking is OFF — see {@link PipelineLatencyWindow}.
     */
    private final PipelineLatencyWindow pipelineLatency;

    /**
     * What the sampler decided, per deadline (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1). Peer of
     * {@link #pipelineLatency} rather than part of it — see {@link DetectionRateWindow} for why the
     * two halves of the rate loop are not one window.
     */
    private final DetectionRateWindow detectionRate;

    /**
     * Chooses the rate {@link #sampleIntervalNanos} schedules deadlines at
     * (docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R2). Built here rather than injected for the same
     * reason {@link #extrapolator} is: it is this pipeline's own per-stream bookkeeping, not a
     * substitutable collaborator.
     */
    private final DetectionRateController rateController;

    /**
     * The clock {@link #pipelineLatency} measures durations with — deliberately <b>not</b> {@link
     * #nanoTimeSource}. That one is a <i>cadence</i> seam: the tests' fake advances one frame
     * interval on every read, which encodes "the pipeline reads me once per frame" and silently
     * skews the measured source rate if anything else reads it. Measuring a duration needs a clock
     * that answers "what time is it" the same way twice, so latency gets its own.
     */
    private final LongSupplier latencyNanoSource;

    /** @see StreamPipelineSettings#cameraHfovDegrees() — {@code 0} disables pose compensation. */
    private final double cameraHfovDegrees;

    // Frame-cadence and detection-outage tuning (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config
    // extraction) -- read from the StreamPipelineSettings supplied to the constructor, defaulting
    // to StreamPipelineSettings#defaults() when the caller doesn't supply one explicitly.
    private final int assumedSourceFps;
    private final double measuredFpsEwmaAlpha;
    private final int warmupFrames;
    private final double minMeasuredFps;
    private final double maxMeasuredFps;
    private final long detectionBackoffInitialNanos;
    private final long detectionBackoffMaxNanos;

    private final AtomicInteger inFlightInferences = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Flow.Subscription subscription;

    /** This pipeline's subscription to {@link #pullDetection}'s result publisher; {@code null} in push mode. */
    private volatile Flow.Subscription pullSubscription;

    private volatile List<Detection> latestDetections = List.of();
    private volatile VideoFrame latestFrame;

    // Only ever touched from within onNext(), which Flow.Subscriber's contract serializes
    // (signals are never delivered concurrently) -- a plain (non-volatile) boolean latch,
    // suppressing repeated WARNING logs for a telemetry supplier that keeps throwing while
    // being read for ego-motion compensation (see readTelemetry()/cameraAttitude()) -- cosmetic,
    // not a resilience concern like a detection failure is.
    private boolean telemetrySupplierFailureLogged = false;

    // Frame-arrival cadence measurement state. Only ever touched from within
    // onNext(), which Flow.Subscriber's contract serializes (signals are
    // never delivered concurrently) -- volatile purely for cross-thread
    // *visibility* between successive onNext calls, not for mutual exclusion.
    private volatile long lastFrameArrivalNanos = -1L;
    private volatile long framesObserved = 0L;
    private volatile double measuredFps = -1.0;

    // Deadline-based sampling state (docs/plans/done/CV-RATE-CONTROL-PLAN.md wave R1), replacing the
    // `sequence % everyNth` stride this class used to sample by. Same threading note as the cadence
    // fields above: written only from onNext, volatile purely for visibility between successive
    // calls. `sampleScheduleArmed` distinguishes "no deadline yet" from a legitimate deadline
    // value, which a sentinel nanotime could not do -- nanoTime's origin is arbitrary and may be
    // negative.
    private volatile boolean sampleScheduleArmed = false;
    private volatile long nextSampleAtNanos;
    private volatile long lastSampleAtNanos;

    // Detection-outage state. Unlike the frame-cadence fields above, this is
    // genuinely touched from multiple threads without serialization: onNext
    // (submitting a new sample) races with detect() completion callbacks
    // (which may land on an arbitrary executor thread). All reads/writes go
    // through the synchronized blocks below rather than volatile/Atomic
    // fields, because entering an outage, doubling the backoff, and reading
    // the backoff deadline must be observed as a single consistent unit --
    // see maybeDetect()/outageDecision()'s javadoc for the race this avoids.
    private final Object outageLock = new Object();
    private boolean inOutage = false;
    private boolean probeInFlight = false;
    private long backoffNanos;
    private long nextProbeAtNanos = 0L;
    private long outageFailureCount = 0L;

    /**
     * The single canonical constructor (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md Finding
     * R1) — every collaborator beyond the eight mandatory arguments (stream/device identity,
     * config, source, and the four core ports) is bundled into {@code collaborators}; see {@link
     * StreamPipelineCollaborators} for what each field controls and {@link
     * StreamPipelineCollaborators#defaults()} for the behavior every pre-R1 shortest constructor
     * used to default to.
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher, StreamPipelineCollaborators collaborators) {
        Objects.requireNonNull(collaborators, "collaborators must not be null");
        this.latencyNanoSource = collaborators.latencyNanoSource();
        this.streamId = Objects.requireNonNull(streamId, "streamId must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.eventEngine = collaborators.eventEngine().orElse(null); // nullable: no detection-event tracking when absent
        this.assetId = collaborators.assetId().orElse(null); // nullable: no owning asset, or live updates not wired
        this.liveUpdatePublisherPort =
                collaborators.liveUpdatePublisherPort().orElse(null); // nullable: no live-update announcements when absent
        this.telemetrySupplier =
                collaborators.telemetrySupplier().orElse(null); // nullable: no ego-motion telemetry input when absent
        this.nanoTimeSource = collaborators.nanoTimeSource();
        this.pullDetection = collaborators.pullDetection().orElse(null); // nullable: push-mode detection when absent (D5/D6)
        StreamPipelineSettings settings = collaborators.settings();
        this.cameraHfovDegrees = settings.cameraHfovDegrees();
        this.assumedSourceFps = settings.assumedSourceFps();
        this.measuredFpsEwmaAlpha = settings.measuredFpsEwmaAlpha();
        this.warmupFrames = settings.warmupFrames();
        this.minMeasuredFps = settings.minMeasuredFps();
        this.maxMeasuredFps = settings.maxMeasuredFps();
        this.detectionBackoffInitialNanos = settings.detectionBackoffInitialNanos();
        this.detectionBackoffMaxNanos = settings.detectionBackoffMaxNanos();
        this.extrapolator =
                new DetectionExtrapolator(settings.extrapolationMaxMillis(), settings.extrapolationMatchGate());
        this.trackBook = new TrackBook(settings.trackRetention());
        this.trackingStats = new TrackingStatsWindow(settings.trackingStatsWindow());
        this.followTracker = new FollowTracker();
        // A lock can already be present in `config` at construction time -- DefaultStreamService.start()
        // folds a requested TrackingConfigPatch (which may itself carry a lock) before this pipeline
        // ever exists, so updateConfig's own lock-change detection never runs for it. Seeding here
        // closes that gap the same way updateConfig would have, had this pipeline already existed.
        if (this.config.tracking().lock() != null) {
            this.followTracker.lockRequested(this.config.tracking().lock());
        }
        this.pipelineLatency = new PipelineLatencyWindow(settings.trackingStatsWindow());
        this.detectionRate = new DetectionRateWindow(settings.trackingStatsWindow(),
                this.pullDetection == null ? DetectionRateWindow.Transport.PUSH : DetectionRateWindow.Transport.PULL);
        this.rateController = new DetectionRateController(settings.adaptiveRate(), settings.cameraHfovDegrees());
        this.backoffNanos = this.detectionBackoffInitialNanos;
        // Seeded from this.config/this.detectionDemand, both already assigned above -- construction
        // itself must never look like a close, only a later, genuine open->closed edge should.
        this.gateWasOpen = detectionGateOpen();
    }

    /**
     * Signals {@link StreamPublisherPort#streamStarted} and subscribes to the
     * source publisher, beginning frame processing — plus, in pull mode
     * (docs/plans/done/MEDIA-SOT-PLAN.md wave M5), subscribing {@link PullResultSubscriber} to {@link
     * #pullDetection}'s result publisher, which is what actually opens the pull (its {@code opener}
     * runs lazily, on this {@code subscribe} call, exactly like the video source's own supervised
     * publisher). Must be called exactly once.
     */
    public void start() {
        streamPublisherPort.streamStarted(streamId, device);
        source.subscribe(this);
        if (pullDetection != null) {
            pullDetection.results().subscribe(new PullResultSubscriber());
        }
    }

    /**
     * @return this pipeline's currently active {@link PipelineConfig} (a volatile read) — the merge
     *         base {@link DefaultStreamService#updateConfig} folds a {@link PipelineConfigPatch}
     *         onto, so a caller never needs to track a running stream's config anywhere but here.
     */
    public PipelineConfig config() {
        return config;
    }

    /**
     * Live-swaps this pipeline's {@link PipelineConfig} (docs/plans/done/CV-CONTROL-PLAN.md &sect;5, &sect;A).
     * Hot knobs — confidence threshold, inference fps, label filter, detection on/off — take effect
     * on the very next sampled/published frame with no lock and no stream/usage-session/SSE
     * disruption, since every per-frame read of {@link #config} already re-reads this volatile
     * field (see that field's own javadoc).
     *
     * <p><b>Model-id re-arm.</b> When {@code next.model().id()} differs from the model this
     * pipeline is currently running, this call also clears this pipeline's own model-bound
     * bookkeeping ({@link #clearDetectionDerivedState()}) — {@link #extrapolator}, {@link
     * #latestDetections}, the track book/stats, and the rate/latency windows — so no stale detection
     * produced by the old model lingers (extrapolated against, persisted, or shown) past the swap;
     * {@link #latestDetections()} reads empty again until the new model's first result completes. The
     * very next sampled frame's {@link #detectionPort}{@code .detect} call already carries {@code
     * next} — including the new model — since {@link DetectionPort}'s own contract runs inference
     * "using the model ... in config" on every call.
     *
     * <p><b>Detection-gate close.</b> When {@code next.detectionEnabled()} is what takes {@link
     * #detectionGateOpen()} from open to closed, this call also clears that same state — see {@link
     * #handleDetectionGateTransition()} — for a different reason than the model-change case above:
     * turning detection off means there will never be another answer to hold the last one against
     * (docs/plans/done/CV-DEMAND-PLAN.md &sect;5/&sect;7), not merely that the next answer will look
     * different.
     *
     * <p><b>Limitation, honestly documented</b> (docs/plans/done/CV-CONTROL-PLAN.md &sect;A's own escape
     * hatch): {@link DetectionPort} (vision-domain) exposes only {@code detect(frame, config)} — no
     * per-stream session lifecycle method a generic caller can invoke, deliberately, per that
     * port's own javadoc ("batching, streaming, and connection reuse are adapter concerns"). This
     * pipeline therefore cannot force whatever adapter-side session a concrete {@code
     * DetectionPort} implementation keeps (e.g. {@code GrpcDetectionPort}'s per-{@link StreamId}
     * bidi call, adapter-cv-grpc/MODULE.md's {@code streamEnded(StreamId)}) to actually close and
     * reopen — that capability exists only on the concrete adapter class, never on the port
     * interface this class (and {@link DefaultStreamService}) are wired against, and this class
     * must not depend on adapter-cv-grpc to reach it. Clearing this pipeline's own state plus
     * passing the new model on every subsequent call is the cleanest re-arm expressible from
     * {@code vision-application} alone; whether a given adapter implementation actually swaps its
     * loaded model on the very next call versus keeping one cached against the stream's first-seen
     * model is that adapter's own concern, verified (at the time of writing) for cv-service, whose
     * {@code ModelRegistry.resolve(request.model_id)} already resolves per-request, not per-session.
     * Should a future adapter ever need an explicit close+reopen signal, that needs a lifecycle
     * method added to {@link DetectionPort} itself — a {@code vision-domain} change, out of this
     * class's file scope.
     *
     * <p><b>{@code FOLLOW} lock changes</b> (docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1/W2) are
     * detected independently of the model-change branch above, by comparing {@code
     * config.tracking().lock()} before and after this call reassigns {@link #config} — {@code
     * TrackingConfigPatch.foldOnto} stamps a freshly allocated {@code lockSeq} on every genuine lock
     * action (including a same-{@code trackId} re-acquire), so object-inequality is exactly "a lock
     * action genuinely occurred", never re-derived here. Reusing the fold's own output rather than
     * reimplementing it is deliberate: this class does not know (and must not need to know) how
     * {@code lockSeq} is allocated. Detected independently of {@code modelChanged} because a single
     * patch may legitimately carry both a model swap and a fresh lock at once — {@link
     * #followTracker} must see the lock either way.
     *
     * @param next the config to switch to
     */
    public void updateConfig(PipelineConfig next) {
        Objects.requireNonNull(next, "next must not be null");
        boolean modelChanged = !config.model().id().equals(next.model().id());
        TargetLock previousLock = config.tracking().lock();
        config = next;
        if (modelChanged) {
            // Track ids and duty-cycle counters describe the model that produced them, so carrying
            // either across a swap would attribute one model's objects and CPU to another's. A
            // tracking-config change (mode, engine, cadences, lock) deliberately clears nothing --
            // tracking is a hot knob like confidence and fps.
            clearDetectionDerivedState();
        }
        TargetLock nextLock = next.tracking().lock();
        if (nextLock != null && !nextLock.equals(previousLock)) {
            followTracker.lockRequested(nextLock);
        }
        handleDetectionGateTransition();
        // docs/plans/done/MEDIA-SOT-PLAN.md wave M5, item 7: PATCH .../config keeps working in pull mode -- its
        // fields travel on the next PullControl via reconfigure() instead of the next FrameRequest,
        // since there is no per-frame outbound call in pull mode to carry them on.
        if (pullDetection != null) {
            pullDetection.port().reconfigure(streamId, next);
        }
    }

    /**
     * Live-swaps this pipeline's detection-demand gate (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.2) — the
     * same no-lock, visible-on-the-next-frame shape as {@link #updateConfig}, and mirroring its
     * argument's own name: {@code demanded}, not {@code enabled}, since this is a fact about
     * consumers, never an operator's own choice. Called only from {@code
     * DefaultStreamService}'s demand-poll task, never the video path.
     *
     * <p>When {@code demanded} is what takes {@link #detectionGateOpen()} from open to closed, this
     * also clears this pipeline's detection-derived state ({@link
     * #handleDetectionGateTransition()}) — a viewer who returns after {@link
     * DetectionState#IDLE_NO_VIEWERS} should see boxes from fresh inference, not a snapshot from
     * whenever the last viewer left.
     *
     * @param demanded whether something is currently consuming this stream's detections
     */
    public void updateDetectionDemand(boolean demanded) {
        this.detectionDemand = demanded;
        handleDetectionGateTransition();
    }

    /**
     * @return whether detection is currently demanded (docs/plans/done/CV-DEMAND-PLAN.md &sect;1) — a
     *         volatile read, {@code true} until/unless {@link #updateDetectionDemand} ever says
     *         otherwise, so a pipeline whose {@code DefaultStreamService} has no {@code
     *         DetectionDemandPort} wired never observes this as {@code false}
     */
    public boolean detectionDemand() {
        return detectionDemand;
    }

    /**
     * Which of the two independent detection gates (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.6)
     * currently explains this stream's boxes-or-no-boxes state — {@link DetectionState#OFF} takes
     * precedence over {@link DetectionState#IDLE_NO_VIEWERS} when both hold, since the operator's own
     * choice is the more specific truth: an operator who disabled detection does not need to also be
     * told nobody is watching. See {@link DetectionState}'s own javadoc for why this reports gating,
     * never health — a stalled {@code DetectionPort} still reads {@link DetectionState#RUNNING}.
     *
     * @return {@link DetectionState#OFF} when {@link PipelineConfig#detectionEnabled()} is {@code
     *         false}; {@link DetectionState#IDLE_NO_VIEWERS} when enabled but {@link
     *         #detectionDemand()} is currently {@code false}; {@link DetectionState#RUNNING} when
     *         both gates are open
     */
    public DetectionState detectionState() {
        if (!config.detectionEnabled()) {
            return DetectionState.OFF;
        }
        return detectionDemand ? DetectionState.RUNNING : DetectionState.IDLE_NO_VIEWERS;
    }

    /**
     * How many frames this pipeline has observed (docs/plans/done/STREAM-STATE-PLAN.md &sect;2.2) —
     * {@code 0} distinguishes "started, nothing has arrived yet" from "frames arrived and stopped,"
     * which {@link StreamState} needs and no other read model exposes.
     *
     * <p>A proxied stream (docs/plans/done/MEDIA-SOT-PLAN.md D4) opens no video source in this JVM
     * and therefore reports {@code 0} forever — a caller must establish observability separately
     * rather than read this as a stall; {@link StreamState#resolve} takes that as its own first
     * parameter for exactly this reason.
     *
     * @return the frame count, never negative; a plain volatile read off the hot path
     */
    public long framesObserved() {
        return framesObserved;
    }

    /**
     * Nanoseconds since the most recent frame arrived, measured against the same injectable
     * {@link #nanoTimeSource} that stamped it — so a test's fake clock governs both ends of the
     * comparison and never mixes a faked stamp with a real {@code System.nanoTime()} reading.
     *
     * @return the elapsed nanoseconds, or {@link Long#MAX_VALUE} when no frame has ever arrived
     *         (the "infinitely stale" answer, which keeps every caller's comparison total without a
     *         separate emptiness check)
     */
    public long nanosSinceLastFrame() {
        long lastArrival = lastFrameArrivalNanos;
        return lastArrival < 0L ? Long.MAX_VALUE : nanoTimeSource.getAsLong() - lastArrival;
    }

    /**
     * @return the most recently completed detection result's detections, or
     *         an empty list if no inference has completed yet. Updated for
     *         every completed inference, including empty results, so callers
     *         never see stale boxes past the point a tracked object
     *         disappeared. Left untouched while a detection outage withholds
     *         frames from the detector, since no inference actually ran.
     */
    public List<Detection> latestDetections() {
        return latestDetections;
    }

    /**
     * @return every track currently booked for this stream (docs/plans/done/TRACKING-PLAN.md &sect;4.E),
     *         ordered by {@code trackId} ascending — an immutable snapshot, the same
     *         read-from-any-thread convention as {@link #latestDetections()}. Empty when tracking is
     *         off, when no tracked detection has arrived yet, or when every track has expired.
     *         Unlike {@link #latestDetections()}, this survives an empty result: a track is a
     *         lifetime, not a frame. See {@link TrackBook} for what booking does and does not mean.
     */
    public List<TrackedObject> tracks() {
        return trackBook.tracks();
    }

    /**
     * @return the current state of whichever {@code FOLLOW} lock this stream's operator holds
     *         (docs/plans/active/TRACK-FOLLOW-PLAN.md &sect;3.1), or {@link Optional#empty()} if no
     *         lock has ever been issued, or the most recent lock action was a release. See {@link
     *         FollowTracker} for the state machine that computes it.
     */
    public Optional<FollowStatus> followStatus() {
        return followTracker.status();
    }

    /**
     * @return this stream's tracking flow over the stats window (docs/plans/done/TRACKING-PLAN.md &sect;4.E) —
     *         detector passes, tracker frames, duty ratio, tracker-latency percentiles, the last
     *         detector reason, the confirmed lock and a state histogram — stamped with the tracking
     *         mode currently configured. {@link TrackingStats#empty} until the first result carrying
     *         {@link com.drones.vision.perception.domain.model.TrackingTelemetry} arrives. Never {@code null}.
     */
    public TrackingStats trackingStats() {
        return trackingStats.snapshot(config.tracking().mode());
    }

    /**
     * @return what this stream's detections actually cost in wall-clock time over the stats window
     *         (docs/conclusions/CV-RATE-BUDGET.md §3): the submit&rarr;available round trip and the
     *         interval between completions, which together bound how old the displayed box is.
     *         Unlike {@link #trackingStats()} this keeps counting with tracking {@code OFF} — a
     *         stream whose boxes lag is very often exactly that stream. {@link
     *         PipelineLatency#empty} until the first result arrives. Never {@code null}.
     */
    public PipelineLatency pipelineLatency() {
        return pipelineLatency.snapshot();
    }

    /**
     * @return why this stream is sampling at the rate it is, over the stats window
     *         (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1) — the source rate, the targeted rate, the
     *         achieved rate, and which of the three losses accounts for any difference. The
     *         companion to {@link #pipelineLatency()}: that one says what a detection cost, this one
     *         says how many were asked for and what became of them. Never {@code null}.
     */
    public DetectionRate detectionRate() {
        return detectionRate.snapshot(sourceFps(), targetFps(), rateController.demandFps());
    }

    /**
     * @return the most recently published frame — exactly the source's own pixels
     *         (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1: there is no server-side rendering stage
     *         anymore), the same instance handed to {@link StreamPublisherPort#publish}
     *         (docs/plans/done/MVP3-PLAN.md C-a) — or {@link Optional#empty()} before the first
     *         frame has published. A latest-wins reference swap, same single-{@code
     *         volatile}-field convention as {@link #latestDetections()}: no copy per frame, no
     *         buffering, and safe to read from any thread (e.g. the HTTP thread serving a
     *         snapshot request) concurrently with {@link #onNext}. Backs both the manager
     *         dashboard's per-stream JPEG snapshot endpoint and training-sample capture — the two
     *         used to want different frames (post- vs. pre-overlay); with no overlay stage they are
     *         the same frame, so {@link #latestRawFrame()} simply mirrors this one.
     */
    public Optional<VideoFrame> latestFrame() {
        return Optional.ofNullable(latestFrame);
    }

    /**
     * @return the most recently published frame — an alias for {@link #latestFrame()}, kept as its
     *         own named method because training-sample capture (docs/plans/done/CV-TRAINING-PLAN.md
     *         §2/§D) reaches this API by this name specifically. Frames are always clean now
     *         (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1) — there is no separate pre-overlay
     *         instance to distinguish anymore.
     */
    public Optional<VideoFrame> latestRawFrame() {
        return Optional.ofNullable(latestFrame);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    @Override
    public void onNext(VideoFrame frame) {
        if (closed.get()) {
            return;
        }
        long now = recordArrival();
        try {
            latestFrame = frame;
            streamPublisherPort.publish(streamId, frame);
            maybeDetect(frame, now);
        } catch (RuntimeException e) {
            handleError(e);
            return;
        }
        if (!closed.get()) {
            subscription.request(1);
        }
    }

    /**
     * This frame's camera attitude for ego-motion compensation, or {@code null} when none can be
     * built (docs/conclusions/CV-RATE-BUDGET.md &sect;5, gap 3).
     *
     * <p>Short-circuits on an unconfigured field of view <b>before</b> touching the supplier: with
     * no optics described the attitude could not drive compensation anyway, so the default
     * deployment pays nothing at all for this feature. This is {@link #telemetrySupplier}'s only
     * remaining consumer (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1 removed the telemetry-OSD
     * burn-in that used to be its other one) — see {@code DefaultStreamService}'s wiring for the
     * INVARIANT that this supplier is still built unconditionally with respect to overlay, gated
     * only on this field of view being configured.
     */
    private CameraAttitude cameraAttitude() {
        if (cameraHfovDegrees <= 0.0) {
            return null;
        }
        return CameraAttitude.from(readTelemetry(), cameraHfovDegrees);
    }

    /** The telemetry supplier read once, with the shared failure latch; {@code null} when absent or failing. */
    private Telemetry readTelemetry() {
        if (telemetrySupplier == null) {
            return null;
        }
        try {
            Telemetry sample = telemetrySupplier.get();
            telemetrySupplierFailureLogged = false;
            return sample;
        } catch (RuntimeException e) {
            if (!telemetrySupplierFailureLogged) {
                telemetrySupplierFailureLogged = true;
                LOG.log(System.Logger.Level.WARNING, () -> "stream " + streamId.value()
                        + " telemetry supplier failed, camera attitude stays unknown until it recovers: "
                        + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Folds this frame's arrival into the source frame-rate measurement and returns the clock
     * reading it used. Cannot throw (deliberately kept outside the publish/detect try-catch below,
     * which only handles collaborator failures).
     *
     * <p><b>This is the only read of {@link #nanoTimeSource} per frame, and the value is threaded
     * onward rather than re-read.</b> That seam is a <i>cadence</i> clock: the tests' fake advances
     * one frame interval on every read, so it encodes "the pipeline reads me once per frame". A
     * second read anywhere in {@code onNext} would silently double every fake source's rate. The
     * same trap already cost {@link #pipelineLatency} a separate clock — see {@link
     * #latencyNanoSource}.
     *
     * @return the arrival timestamp of this frame, for {@link #maybeDetect}'s deadline
     */
    private long recordArrival() {
        long now = nanoTimeSource.getAsLong();
        framesObserved++;
        if (lastFrameArrivalNanos >= 0) {
            long deltaNanos = now - lastFrameArrivalNanos;
            if (deltaNanos > 0) {
                double instantaneousFps = 1_000_000_000.0 / deltaNanos;
                double blended = measuredFps < 0
                        ? instantaneousFps // seed directly from the first sample rather than blending toward an
                                            // arbitrary starting value, so a constant-cadence source converges
                                            // immediately instead of drifting in slowly over many EWMA updates
                        : measuredFpsEwmaAlpha * instantaneousFps + (1 - measuredFpsEwmaAlpha) * measuredFps;
                measuredFps = clamp(blended, minMeasuredFps, maxMeasuredFps);
            }
        }
        lastFrameArrivalNanos = now;
        return now;
    }

    /**
     * @return the source frame rate to report and to bound the sample rate by: the measurement once
     *         it is trusted ({@link StreamPipelineSettings#warmupFrames()} arrivals observed), the
     *         configured assumption until then.
     */
    private double sourceFps() {
        boolean trustMeasurement = framesObserved >= warmupFrames && measuredFps >= 0;
        return trustMeasurement ? measuredFps : assumedSourceFps;
    }

    /**
     * Decides whether this frame serves a sample deadline, advancing the schedule when it does.
     *
     * <h2>Why a deadline and not {@code sequence % everyNth}</h2>
     * The stride this replaces was {@code max(1, round(sourceFps / targetFps))} — an <b>integer</b>,
     * so a 24 fps source asked for 10 fps could only ever be sampled at 12 or 8, never 10, and the
     * stride was recomputed from a drifting EWMA on every frame, which moved the phase of {@code
     * sequence % N} as well as its period. A deadline has neither problem: every deadline is served
     * by exactly one frame, so the achieved rate equals the target for any source faster than it
     * (docs/plans/done/CV-RATE-CONTROL-PLAN.md &sect;1, losses L1/L2).
     *
     * <h2>The late clamp</h2>
     * When a frame arrives more than a whole interval after the deadline it serves, the schedule is
     * restarted from {@code now} rather than advanced by one interval. Without that clamp a stalled
     * source builds up deadline debt that fires as a catch-up burst on recovery — spending the
     * scarcest resource in the system on frames whose moment has passed. The skipped deadlines are
     * counted instead, because "the source cannot feed this rate" is a distinct diagnosis from
     * "the detector cannot keep up" and has a different fix.
     */
    private boolean sampleDue(long now) {
        long intervalNanos = sampleIntervalNanos();
        if (!sampleScheduleArmed) {
            sampleScheduleArmed = true;
            armScheduleAt(now, now + intervalNanos);
            return true;
        }
        if (now > lastSampleAtNanos && now < nextSampleAtNanos) {
            return false;
        }
        if (now <= lastSampleAtNanos) {
            // Degenerate clock (frozen, or stepped backwards): with no usable time base there is no
            // rate to limit, so fail OPEN and sample. Failing closed would silently stop detection
            // altogether the moment a clock skewed -- the loudest possible failure for the quietest
            // possible cause. The old frame-count stride was immune to this by construction; a
            // time-based schedule has to say what it does instead.
            armScheduleAt(now, now + intervalNanos);
            return true;
        }
        long advanced = nextSampleAtNanos + intervalNanos;
        if (advanced <= now) {
            detectionRate.recordMissedDeadlines((now - nextSampleAtNanos) / intervalNanos);
            advanced = now + intervalNanos;
        }
        armScheduleAt(now, advanced);
        return true;
    }

    /** Records that {@code now} served a deadline and that the next one falls at {@code nextAtNanos}. */
    private void armScheduleAt(long now, long nextAtNanos) {
        lastSampleAtNanos = now;
        nextSampleAtNanos = nextAtNanos;
    }

    /** The gap between sample deadlines for the currently targeted rate; at least one nanosecond. */
    private long sampleIntervalNanos() {
        return Math.max(1L, Math.round(NANOS_PER_SECOND / targetFps()));
    }

    /**
     * The rate being sampled at right now: {@link #effectiveInferenceFps()} raised by {@link
     * #rateController} when the tracked target is about to leave its association budget, and exactly
     * {@link #effectiveInferenceFps()} whenever it is not (or the loop is off). Read on every frame,
     * so it is deliberately a few comparisons over volatile fields rather than any kind of scan.
     */
    private double targetFps() {
        return rateController.targetFps(effectiveInferenceFps(), sourceFps(), config.maxInFlightInferences());
    }

    /**
     * The rate this pipeline actually samples frames at (docs/plans/done/TRACKING-PLAN.md &sect;5.D): {@link
     * PipelineConfig#inferenceFps()} normally, but {@code max(inferenceFps, followFps)} in {@link
     * TrackingMode#FOLLOW} — in {@code FOLLOW} the tracker wants frames faster than the detector
     * does, and raising the sample rate is the whole of that change because {@link
     * #sampleIntervalNanos} derives the deadline gap from this value on every frame, reading the
     * volatile config live.
     *
     * <p>{@code OFF} and {@code ASSOCIATE} return {@code inferenceFps()} unchanged, so a stream that
     * is not following samples exactly as it did before tracking existed. {@code followFps} defaults
     * to 15 rather than "every frame" deliberately — see &sect;5.D for the bandwidth reasoning.
     *
     * <p>Package-private rather than private so the same-package test can assert the three modes
     * directly instead of inferring the rate from frame counts.
     */
    int effectiveInferenceFps() {
        TrackingConfig tracking = config.tracking();
        return tracking.mode() == TrackingMode.FOLLOW
                ? Math.max(config.inferenceFps(), tracking.followFps())
                : config.inferenceFps();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Outcome of consulting outage state for a newly-sampled frame, see {@link #outageDecision()}. */
    private enum OutageDecision {
        /** No outage in progress: fall through to the normal in-flight-bounded path. */
        NORMAL,
        /** An outage is in progress but the backoff hasn't elapsed, or a probe is already outstanding. */
        SKIP,
        /** The backoff deadline has passed and no probe is outstanding: send exactly this one frame as a probe. */
        PROBE
    }

    /**
     * The same two-gate conjunction {@link #maybeDetect} and {@link PullResultSubscriber#onNext}
     * both gate on (docs/plans/done/CV-DEMAND-PLAN.md &sect;1, &sect;3.2): {@link
     * PipelineConfig#detectionEnabled()} (the operator's own per-stream choice) <b>and</b> {@link
     * #detectionDemand()} (the system-derived "someone is actually watching" fact). Pulled into one
     * method, read from both places, so push and pull mode can never drift out of sync about what
     * "detection is gated off" means — see the class javadoc's pull-mode section and each caller's
     * own javadoc for what each does once the gate is closed.
     */
    private boolean detectionGateOpen() {
        return config.detectionEnabled() && detectionDemand;
    }

    /**
     * Detects a true&rarr;false transition of {@link #detectionGateOpen()} and, exactly when one
     * occurs, clears every piece of detection-derived state a consumer could otherwise keep reading
     * as fresh ({@link #clearDetectionDerivedState()}) — docs/plans/done/CV-DEMAND-PLAN.md
     * &sect;5/&sect;7's gate-close-clearing correction: turning detection off (or losing the last
     * viewer) means there will never be another answer, so holding the last one and re-serving it
     * forever — the reported "turn on and off doesn't work" complaint — asserts something false.
     * Called from both {@link #updateConfig} (operator intent) and {@link #updateDetectionDemand}
     * (viewer demand); the gate has two independent inputs and either can be the one that closes it,
     * so the transition check lives here once instead of being duplicated at each call site.
     *
     * <p>This is genuinely different from an <b>outage</b> ({@link #onDetectionFailure}): during an
     * outage the system is still trying and simply has no fresher answer <i>yet</i>, so holding the
     * last one is the honest thing, and that state is deliberately left untouched (see the outage
     * Gotcha in this module's {@code MODULE.md}). A closed gate has no "yet" — nothing will try again
     * until it reopens — so the two cases clear differently on purpose.
     *
     * <p>Keyed off {@link #detectionGateOpen()}'s current value compared against the <em>last
     * observed</em> one ({@link #gateWasOpen}), not "which setter ran": {@link #updateConfig} is
     * called for plain confidence/fps/label-filter patches too, and {@link #updateDetectionDemand} is
     * called on every demand-poll tick regardless of whether demand actually changed, so only a
     * genuine open&rarr;closed <i>edge</i> may clear anything — a repeated {@code false} (an operator
     * who already turned detection off patching the confidence threshold, or a poll tick
     * re-confirming "still nobody watching" every 2s) must be a no-op here, not a re-clearing thrash.
     * {@link #gateLock} makes the read-compare-write atomic across the two callers' different threads.
     */
    private void handleDetectionGateTransition() {
        boolean open = detectionGateOpen();
        synchronized (gateLock) {
            if (gateWasOpen && !open) {
                clearDetectionDerivedState();
            }
            gateWasOpen = open;
        }
    }

    /**
     * Clears every piece of detection-derived state a consumer could otherwise read as fresh: the
     * raw result ({@link #latestDetections}), the extrapolator's own bookkeeping ({@link
     * #extrapolator}), the track book/stats, the follow tracker, and the rate/latency windows.
     * Shared by two call sites that reach it for
     * different reasons — {@link #updateConfig}'s model-id re-arm and {@link
     * #handleDetectionGateTransition}'s gate close — both boiling down to the same fact: nothing
     * already held describes what this pipeline is about to (or will never again) produce. A held
     * {@code FOLLOW} lock is no exception: its bound {@code trackId} was allocated by whatever the
     * detector was feeding before the re-arm/close, so it means nothing after.
     */
    private void clearDetectionDerivedState() {
        extrapolator.reset();
        latestDetections = List.of();
        trackBook.clear();
        trackingStats.clear();
        followTracker.clear();
        pipelineLatency.clear();
        detectionRate.clear();
        rateController.clear();
    }

    /**
     * Gated first — before the outage/in-flight logic below — on {@link #detectionGateOpen()}: either
     * conjunct being {@code false} returns immediately, so a disabled or undemanded stream spends
     * zero CPU on inference <i>and</i> stops probing during an outage too — nothing below this check
     * ever runs. The two gates are deliberately independent conjuncts rather than one merged flag —
     * see {@code DetectionDemandPort}'s own javadoc for why collapsing them would be wrong. Either
     * one flipping back resumes detection on the next sampled frame, exactly where the (frozen,
     * untouched) outage/backoff state left off.
     *
     * <p>Also gated on {@link #pullDetection} being absent (docs/plans/done/MEDIA-SOT-PLAN.md wave M5): in
     * pull mode the worker runs its own (ported) deadline sampler and decides when to detect, so this
     * pipeline's own sampler/in-flight bound/{@link #detectionPort} submission never runs — {@link
     * PullResultSubscriber} is the whole of pull-mode detection. This is the one line push mode's own
     * behavior depends on being a no-op for: {@code pullDetection} is {@code null} for every existing
     * caller, so the check below always falls through exactly as it did before this capability existed.
     */
    private void maybeDetect(VideoFrame frame, long now) {
        if (!detectionGateOpen() || pullDetection != null) {
            return;
        }
        switch (outageDecision()) {
            case PROBE -> {
                // A probe is a LIVENESS check, not a sample: its cadence is the outage backoff, so
                // it deliberately ignores the sample deadline. Letting the sampler gate it too would
                // make two independent schedules interfere -- the backoff says "probe now" while the
                // sampler says "not yet" -- delaying recovery by up to a full sample interval for no
                // benefit. The backoff (>=1s) is always far longer than a sample interval, so this
                // can never probe faster than the outage logic intends.
                detectionRate.record(DetectionRateWindow.Outcome.SUBMITTED, now);
                submitDetection(frame, true);
            }
            case SKIP -> {
                // Still backing off, or a probe is already in flight: never counted as in-flight.
                // Consulted through the deadline so the counter stays comparable with the others --
                // one entry per deadline, not one per frame arriving during a ten-second backoff.
                if (sampleDue(now)) {
                    detectionRate.record(DetectionRateWindow.Outcome.DROPPED_OUTAGE, now);
                }
            }
            case NORMAL -> {
                if (!sampleDue(now)) {
                    return;
                }
                if (inFlightInferences.get() >= config.maxInFlightInferences()) {
                    // bounded in-flight: skip this sample rather than queue it
                    detectionRate.record(DetectionRateWindow.Outcome.DROPPED_IN_FLIGHT, now);
                    return;
                }
                inFlightInferences.incrementAndGet();
                detectionRate.record(DetectionRateWindow.Outcome.SUBMITTED, now);
                submitDetection(frame, false);
            }
        }
    }

    /**
     * Consults and, where it decides {@link OutageDecision#PROBE}, mutates
     * outage state under {@link #outageLock} in a single atomic step —
     * checking {@code nextProbeAtNanos} and claiming {@code probeInFlight}
     * together, rather than as two separate lock-free reads, is what
     * prevents a second concurrent probe from starting (or the very first
     * backoff window from being skipped by a racing thread that observes
     * {@code inOutage} freshly flipped {@code true} before its paired
     * backoff fields are visible).
     */
    private OutageDecision outageDecision() {
        synchronized (outageLock) {
            if (!inOutage) {
                return OutageDecision.NORMAL;
            }
            if (probeInFlight || nanoTimeSource.getAsLong() < nextProbeAtNanos) {
                return OutageDecision.SKIP;
            }
            probeInFlight = true;
            return OutageDecision.PROBE;
        }
    }

    private void submitDetection(VideoFrame frame, boolean isProbe) {
        long submittedAtNanos = latencyNanoSource.getAsLong();
        // The three-argument form is taken ONLY when there is an attitude to send, so a port (or a
        // test double) that never learned about attitude sees exactly the calls it saw before this
        // feature existed. The additive contract holds at the call site, not just in the interface.
        CameraAttitude attitude = cameraAttitude();
        rateController.observeAttitude(attitude, submittedAtNanos);
        CompletionStage<DetectionResult> pending = attitude == null
                ? detectionPort.detect(frame, config)
                : detectionPort.detect(frame, config, attitude);
        pending.whenComplete((result, error) -> {
            // Recorded before the closed/error branches below: a round trip that ended in a failure,
            // or arrived after close, still happened and is still the number worth seeing.
            long completedAtNanos = latencyNanoSource.getAsLong();
            pipelineLatency.record(submittedAtNanos, completedAtNanos);
            rateController.recordRoundTrip(completedAtNanos - submittedAtNanos);
            if (isProbe) {
                clearProbeInFlight();
            } else {
                inFlightInferences.decrementAndGet();
            }
            if (closed.get()) {
                return;
            }
            if (error != null) {
                onDetectionFailure(unwrap(error), isProbe);
            } else {
                onDetectionSuccess(result);
            }
        });
    }

    private void clearProbeInFlight() {
        synchronized (outageLock) {
            probeInFlight = false;
        }
    }

    /**
     * Handles a failed inference. The first failure (from any in-flight
     * call, probe or not) enters the outage and is the only one that raises
     * a {@code PIPELINE_ERROR} event; every later failure while already in
     * outage is counted silently, and only a failed <b>probe</b> doubles the
     * backoff (capped at {@link #MAX_BACKOFF_NANOS}) and reschedules the
     * next probe — a stray failure from a call that was already in flight
     * when the outage began must not perturb a backoff a probe may have
     * already advanced.
     */
    private void onDetectionFailure(Throwable throwable, boolean isProbe) {
        boolean enteringOutage;
        synchronized (outageLock) {
            enteringOutage = !inOutage;
            if (enteringOutage) {
                inOutage = true;
                backoffNanos = detectionBackoffInitialNanos;
                outageFailureCount = 0;
                nextProbeAtNanos = nanoTimeSource.getAsLong() + backoffNanos;
            } else {
                outageFailureCount++;
                if (isProbe) {
                    backoffNanos = Math.min(backoffNanos * 2, detectionBackoffMaxNanos);
                    nextProbeAtNanos = nanoTimeSource.getAsLong() + backoffNanos;
                }
            }
        }
        if (enteringOutage) {
            eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR,
                    "detection outage: " + describeFailure(throwable)));
        }
    }

    /**
     * Handles a successful inference (normal sample or outage-ending probe
     * alike). Always resets the backoff to its initial interval, and —
     * exactly when this success followed an active outage — clears the
     * outage and logs a recovery line (no second event is emitted; {@code
     * PIPELINE_ERROR} is reserved for the outage's start).
     */
    private void onDetectionSuccess(DetectionResult result) {
        boolean recovered;
        long failuresDuringOutage;
        synchronized (outageLock) {
            recovered = inOutage;
            failuresDuringOutage = outageFailureCount;
            inOutage = false;
            backoffNanos = detectionBackoffInitialNanos;
        }
        if (recovered) {
            LOG.log(System.Logger.Level.INFO, () -> "stream " + streamId.value() + " detection recovered after "
                    + failuresDuringOutage + " failed attempt(s) during the outage");
        }
        onDetectionResult(result);
    }

    /**
     * Fans out one completed result — after enforcing {@link PipelineConfig#labelFilter()}/{@link
     * PipelineConfig#labelDenyFilter()} exactly once, centrally, here (docs/plans/done/CV-CONTROL-PLAN.md
     * &sect;A, the dormant-field fix; deny-list joined docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) —
     * to every downstream consumer: {@link #latestDetections()}, {@link #extrapolator}, {@link
     * #trackBook}, {@link #trackingStats}, {@link #followTracker}, {@link #eventEngine}, {@link
     * #liveUpdatePublisherPort}, and persistence/the {@code DETECTION} event. Filtering once here,
     * before any of those, is what
     * makes every consumer see the same filtered set uniformly instead of each having to know about
     * {@code labelFilter}/{@code labelDenyFilter} itself — screen, alerts and recording all stay
     * consistent because there is exactly one drop site.
     *
     * <p>This list is a <b>fan-out of consumers by design</b>: adding one is not a new
     * responsibility for this class (TRACKING-ORCHESTRATION.md &sect;2.3). The tracking work
     * genuinely lives in {@link TrackBook}/{@link TrackingStatsWindow}, carved as peers of {@link
     * DetectionExtrapolator} so the decomposition this class is queued for inherits well-shaped
     * perception stages rather than a fatter method.
     *
     * <p><b>Re-checks {@link #detectionGateOpen()} on entry</b> (docs/plans/done/CV-DEMAND-PLAN.md
     * &sect;5/&sect;7): both callers already gate before reaching here — {@link #maybeDetect} before
     * submitting, {@link PullResultSubscriber#onNext} before forwarding — but a push-mode inference
     * submitted while the gate was open can complete on an arbitrary executor thread ({@link
     * #submitDetection}'s {@code whenComplete}) <i>after</i> the gate has since closed and {@link
     * #handleDetectionGateTransition()} has already cleared this pipeline's state. Applying such a
     * result here would silently resurrect exactly what was just cleared, so a closed gate drops it
     * instead — symmetrically with the drop {@link PullResultSubscriber#onNext} already does for the
     * (much narrower) equivalent window in pull mode. This does not touch the outage/backoff
     * bookkeeping in {@link #onDetectionSuccess}, which runs before this call regardless of the gate —
     * detector health is a different, deliberately gate-independent concern (see the outage Gotcha).
     */
    private void onDetectionResult(DetectionResult result) {
        if (!detectionGateOpen()) {
            return;
        }
        DetectionResult filtered = applyLabelFilters(result);
        latestDetections = filtered.detections();
        extrapolator.accept(filtered);
        trackBook.accept(filtered);
        trackingStats.accept(filtered);
        followTracker.accept(filtered);
        rateController.observeDetections(filtered.detections(), config.tracking().redetectIouPercent());
        if (eventEngine != null) {
            eventEngine.accept(filtered);
        }
        if (liveUpdatePublisherPort != null && assetId != null) {
            liveUpdatePublisherPort.publishDetections(assetId, filtered);
        }
        if (!filtered.detections().isEmpty()) {
            detectionRepositoryPort.save(filtered);
            eventPublisher.publish(Event.of(streamId, EventType.DETECTION,
                    "Detected " + filtered.detections().size() + " object(s) on frame " + filtered.frameSequence()));
        }
    }

    /**
     * Drops every detection whose label either fails a non-empty {@link PipelineConfig#labelFilter()}
     * or matches {@link PipelineConfig#labelDenyFilter()} (docs/plans/done/CV-CLEAN-FEED-PLAN.md
     * D-2) — an empty allowlist keeps everything (unchanged semantics), an empty deny-list denies
     * nothing. The two answer different questions: the allowlist, when non-empty, is the model-intent
     * seed ("only these labels ever exist for this stream"); the deny-list is the everyday "hide this
     * class" act, and writing to it never touches the allowlist — a class not yet observed keeps
     * appearing instead of being silently swept into an enumerated allowlist complement (the defect
     * docs/plans/done/CV-UX-RESEARCH.md &sect;4.4 diagnosed). Returns {@code result} unchanged
     * (same instance) when nothing was actually dropped, so the common case (neither filter
     * configured, or every detection already matches) allocates nothing new.
     *
     * <p>The rebuilt result carries {@link DetectionResult#tracking()} through unchanged: a label
     * filter drops <i>detections</i>, and per-frame tracking facts (whether the detector ran, why,
     * what the tracker cost, which track is locked) are true of the frame regardless of which of its
     * boxes survived filtering. Dropping them here would silently zero the duty-cycle stats of every
     * stream that happens to use a label filter.
     */
    private DetectionResult applyLabelFilters(DetectionResult result) {
        Set<String> labelFilter = config.labelFilter();
        Set<String> labelDenyFilter = config.labelDenyFilter();
        if (labelFilter.isEmpty() && labelDenyFilter.isEmpty()) {
            return result;
        }
        List<Detection> kept = result.detections().stream()
                .filter(d -> (labelFilter.isEmpty() || labelFilter.contains(d.label()))
                        && !labelDenyFilter.contains(d.label()))
                .toList();
        if (kept.size() == result.detections().size()) {
            return result;
        }
        return new DetectionResult(result.streamId(), result.frameSequence(), result.capturedAt(), kept,
                result.inferenceLatency(), result.tracking());
    }

    /**
     * Folds a pull result's worker-reported diagnostics into {@link #detectionRate}/{@link
     * #pipelineLatency}/{@link #rateController} — the pull-mode analogue of what {@link
     * #submitDetection}'s completion callback does for push mode (docs/plans/done/MEDIA-SOT-PLAN.md &sect;7).
     * A {@code null} {@link DetectionResult#pullTelemetry()} (a malformed/early response,
     * {@link DetectionFrameCodec}'s own all-zero-means-absent case) is skipped rather than guessed at.
     *
     * <p>{@code capture_skew_millis} has no read-model home this wave (&sect;5.4 stays frozen) — logged
     * at DEBUG, not stored.
     */
    private void recordPullTelemetry(DetectionResult result) {
        PullTelemetry telemetry = result.pullTelemetry();
        if (telemetry == null) {
            return;
        }
        long completedAtNanos = latencyNanoSource.getAsLong();
        detectionRate.recordPull(telemetry, completedAtNanos);
        Instant receivedAt = pullDetection.wallClock().get();
        long roundTripNanos = Math.max(0L, Duration.between(result.capturedAt(), receivedAt).toNanos());
        pipelineLatency.recordPullRoundTrip(completedAtNanos, roundTripNanos);
        // §7: no Java -> Python round trip exists in pull mode, so the capacity ceiling the rate
        // controller uses is fed from the worker's own reported cost instead of a measured
        // submit->available duration -- DetectionRateController itself is unchanged.
        long ceilingNanos = Duration.ofMillis(telemetry.decodeMillis()).plus(result.inferenceLatency()).toNanos();
        rateController.recordRoundTrip(ceilingNanos);
        LOG.log(System.Logger.Level.DEBUG, () -> "stream " + streamId.value() + " capture_skew_millis="
                + telemetry.captureSkewMillis());
    }

    /**
     * Pull-mode detection driver (docs/plans/done/MEDIA-SOT-PLAN.md wave M5, D5/D6): subscribes to {@link
     * #pullDetection}'s live result publisher and forwards every arriving {@link DetectionResult} to
     * the same {@link #onDetectionResult} fan-out push mode uses, after folding its worker-reported
     * diagnostics into the rate/latency windows ({@link #recordPullTelemetry}). Requests one item at a
     * time, mirroring this pipeline's own video-path backpressure discipline ({@link #onSubscribe}).
     *
     * <p><b>Gated on {@link #detectionGateOpen()}</b> (docs/plans/done/CV-DEMAND-PLAN.md &sect;5's
     * "one honest gap", closed at the application layer): in pull mode the worker owns its own
     * sampling loop, so {@link #maybeDetect} never runs for this pipeline and this is the only place
     * left to apply the operator/demand gate. Before this gate existed every arriving result was
     * forwarded unconditionally regardless of {@link PipelineConfig#detectionEnabled()} or {@link
     * #detectionDemand()} — {@link #detectionState()} could read {@code OFF} while boxes kept
     * arriving, because nothing between the worker and the consumer ever checked. A gated-off result
     * is <b>dropped, not buffered</b> — this project's failsafe rule is newest-data-wins (see
     * CLAUDE.md &sect;9), and a result held during an off period would already be stale by the time
     * detection resumes, so there is nothing worth keeping it for.
     *
     * <p><b>{@link #recordPullTelemetry} is skipped along with the forward</b>, not just {@link
     * #onDetectionResult} — deliberately, not merely for symmetry. The worker genuinely did the
     * decode/inference work behind a gated-off result, but {@link #detectionRate} is a <i>consumer-
     * facing</i> read model, the counterpart {@link #detectionState()} sits beside on the same
     * response: recording it here would report a healthy {@code submittedFps} for a stream the state
     * says is {@code OFF}, reintroducing the exact class of lie this gate exists to close, just moved
     * from the video overlay into the rate panel instead of fixed. Push mode already sets this
     * precedent unprompted — {@link #maybeDetect}'s own early return means {@link #detectionRate}
     * never observes a single sample while a push stream is gated off, so a gated pull stream now
     * reports the same "nothing submitted" honesty. The worker still burns the CPU regardless (a
     * cross-language follow-up wave, not fixable from here — see docs/plans/done/CV-DEMAND-PLAN.md
     * &sect;5); that cost is real but is not this read model's job to surface.
     *
     * <p><b>{@link #latestDetections}/{@link #extrapolator}/{@link #trackBook} are cleared</b> the
     * moment the gate closes, in both transports alike — not merely left to freeze. This class's
     * first cut left them frozen at their last value, reasoning (wrongly) that push mode's own
     * behavior was the reference to match; it was instead a shared defect, not a precedent, per
     * CLAUDE.md &sect;9 ("newest data ... should be used, even if previous is still available"). An
     * outage genuinely differs — the detector is still trying and simply has no fresher answer
     * <i>yet</i>, so holding the last one there is honest (see the class javadoc's error-handling
     * section) — but a closed gate has no "yet": nothing will try again until it reopens, so holding
     * the last result and re-serving it to every poll forever asserts something false. The clearing
     * itself happens in {@link #handleDetectionGateTransition()}, called from both {@link
     * #updateConfig} and {@link #updateDetectionDemand} on whichever one detects the open&rarr;closed
     * edge, so this subscriber does not duplicate it — it only has to stop forwarding.
     *
     * <p>{@code onError}/{@code onComplete} reuse {@link #handleError}/{@link #close()} exactly as the
     * video-path {@link Flow.Subscriber} methods do — in practice these fire only when {@link
     * #pullDetection}'s publisher is not itself a reopen-with-backoff {@code SupervisedPublisher} (a
     * hand-faked port in a test), since {@code SupervisedPublisher} never forwards a terminal signal
     * downstream (see its own javadoc).
     */
    private final class PullResultSubscriber implements Flow.Subscriber<DetectionResult> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            pullSubscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(DetectionResult result) {
            if (closed.get()) {
                return;
            }
            if (detectionGateOpen()) {
                recordPullTelemetry(result);
                onDetectionResult(result);
            }
            if (!closed.get()) {
                pullSubscription.request(1);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            handleError(throwable);
        }

        @Override
        public void onComplete() {
            close();
        }
    }

    @Override
    public void onError(Throwable throwable) {
        handleError(throwable);
    }

    @Override
    public void onComplete() {
        close();
    }

    private void handleError(Throwable throwable) {
        if (closed.compareAndSet(false, true)) {
            eventPublisher.publish(Event.of(streamId, EventType.PIPELINE_ERROR, describeFailure(throwable)));
            doClose();
        }
    }

    private static String describeFailure(Throwable throwable) {
        return throwable == null ? "unknown error" : String.valueOf(throwable.getMessage());
    }

    /**
     * Stops the pipeline: cancels the source subscription and signals {@link
     * StreamPublisherPort#streamEnded}. Idempotent — safe to call multiple
     * times and from any thread, including concurrently with an in-flight
     * {@code onNext} or detection completion.
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            doClose();
        }
    }

    private void doClose() {
        Flow.Subscription s = subscription;
        if (s != null) {
            s.cancel();
        }
        Flow.Subscription pulled = pullSubscription;
        if (pulled != null) {
            pulled.cancel();
        }
        streamPublisherPort.streamEnded(streamId);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
