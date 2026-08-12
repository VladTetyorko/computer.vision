package com.drones.vision.application.pipeline;

import com.drones.vision.domain.model.AnnotatedFrame;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CameraAttitude;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.PullTelemetry;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.TrackedObject;
import com.drones.vision.domain.model.TrackingConfig;
import com.drones.vision.domain.model.TrackingMode;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;

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
import com.drones.vision.application.stream.DefaultStreamService;
import com.drones.vision.application.stream.PipelineConfigPatch;
import com.drones.vision.application.stream.StreamService;

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
 *   <li><b>Overlay burn-in</b> (docs/plans/done/MVP1-PLAN.md §C8, smoothed per
 *       docs/main/CYCLES-PLAN.md §12 CP-c; optional per docs/plans/done/MVP2-PLAN.md §V,
 *       V-e; telemetry OSD input added later): when an {@link OverlayPort}
 *       is configured (constructor argument, nullable — {@code null} keeps
 *       today's raw-publish behavior everywhere) and {@link
 *       PipelineConfig#overlayBurnIn()} is {@code true} (the default) and
 *       either {@link #extrapolator}'s boxes at this frame's capture time
 *       are non-empty or a telemetry sample resolves (see below), the frame
 *       is rendered through {@link OverlayPort#render} — as an {@link
 *       AnnotatedFrame} carrying the extrapolated detections and that
 *       telemetry sample — before being published. The extrapolator tracks
 *       the two most recently completed results and, between them, moves
 *       each matched box toward where it is predicted to be at the
 *       publishing frame's timestamp instead of freezing it at its last
 *       detected position — see {@link DetectionExtrapolator} for the
 *       matching/velocity/cap details; {@link #latestDetections()} (the
 *       REST-facing surface) is unaffected, it always returns the raw
 *       latest result. The telemetry sample burned in — activating {@link
 *       PipelineConfig#overlayTelemetry()}'s OSD gate — comes from an
 *       optional {@code telemetrySupplier} (constructor argument, nullable):
 *       when {@link #overlayPort} is configured, {@link
 *       PipelineConfig#overlayTelemetry()} is {@code true}, and a supplier
 *       was given, it is invoked once per published frame — it must
 *       therefore be cheap (an in-memory read, never I/O) — and its result
 *       (possibly {@code null}, meaning "no sample right now") is what's
 *       passed as {@link AnnotatedFrame#telemetry()}; any of the three
 *       absent yields {@code null} exactly as before this input existed. A
 *       supplier that throws is treated exactly like "no sample available"
 *       (caught, {@code null} used instead) — never a pipeline failure —
 *       with the same once-per-failure-run {@code WARNING} throttling the
 *       renderer itself uses (see below). Without an {@code OverlayPort}
 *       (the default), before any detection has completed and with no
 *       telemetry sample either, the raw frame is published unchanged,
 *       exactly as before this feature. A renderer that throws is treated
 *       as a purely cosmetic failure, never a pipeline failure: the raw
 *       frame is published instead, and at most one {@code WARNING} is
 *       logged per failure run (a boolean latch, reset the next time
 *       rendering succeeds) so a persistently broken renderer never spams
 *       logs on every frame.</li>
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
 * multiple of (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1).
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
 * was just handed (post-overlay burn-in when one was drawn), a latest-wins reference swap with no
 * per-frame copy. This is what backs the manager dashboard's per-stream JPEG snapshot endpoint.
 *
 * <p><b>Debounced detection events</b> (docs/plans/done/MVP2-PLAN.md §E, E-a): every completed result —
 * empty or not — also feeds an optional {@link DetectionEventEngine} ({@code eventEngine},
 * nullable, same convention as {@code overlayPort}), which collapses a tracked label's
 * consecutive-qualifying-results streak into an open/close {@code DetectionEvent} lifecycle. This
 * is a separate concern from {@link #latestDetections()}/overlay burn-in: the engine only ever
 * reads results, it never influences what gets published or returned from this class.
 *
 * <p><b>Tracking</b> (docs/plans/done/TRACKING-PLAN.md &sect;5.D/&sect;5.E): two further consumers on that same
 * fan-out. {@link TrackBook} keeps this stream's tracks by id with their lifetimes ({@link
 * #tracks()}); {@link TrackingStatsWindow} keeps rolling duty-cycle counters over the {@link
 * com.drones.vision.domain.model.TrackingTelemetry} riding each result ({@link #trackingStats()}).
 * Both are cleared on a model re-arm, exactly as {@link #extrapolator} is. Tracking also reaches the
 * sampling logic above through one value: {@link #effectiveInferenceFps()}, which raises the sample
 * rate to {@code followFps} while the stream is in {@link TrackingMode#FOLLOW}. Nothing else in this
 * class knows tracking exists — no branch in the publish path, none in {@link #maybeDetect}.
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
     * detectionEnabled}, {@link #overlayIfNeeded}'s overlay flags, and the {@code config} passed
     * into {@link #detectionPort}{@code .detect}) re-reads this field directly, so a write from
     * {@link #updateConfig} is visible to the very next frame with no lock and no restart. See
     * {@link #updateConfig}'s own javadoc for the model-id re-arm case.
     */
    private volatile PipelineConfig config;
    private final Flow.Publisher<VideoFrame> source;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final OverlayPort overlayPort;
    private final DetectionEventEngine eventEngine;
    private final AssetId assetId;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;
    private final Supplier<Telemetry> telemetrySupplier;
    private final LongSupplier nanoTimeSource;
    private final DetectionExtrapolator extrapolator;

    /**
     * Pull-mode detection driver (docs/plans/active/MEDIA-SOT-PLAN.md wave M5, D5/D6) — {@code null} means push
     * mode: {@link #maybeDetect} samples frames and calls {@link #detectionPort} directly, unchanged.
     * Non-null switches this pipeline to {@link PullResultSubscriber}, which subscribes to {@link
     * PullDetectionBinding#results()} and forwards every arriving result to the same {@link
     * #onDetectionResult} fan-out push mode uses — the seam is here and in {@link #maybeDetect}'s
     * guard, never inside {@link #onDetectionResult} itself.
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

    /**
     * Wall-clock cost of the detection round trip, as opposed to the compute cost cv-service
     * self-reports (docs/conclusions/CV-RATE-BUDGET.md §3). Separate from {@link #trackingStats}
     * because it must keep counting when tracking is OFF — see {@link PipelineLatencyWindow}.
     */
    private final PipelineLatencyWindow pipelineLatency;

    /**
     * What the sampler decided, per deadline (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1). Peer of
     * {@link #pipelineLatency} rather than part of it — see {@link DetectionRateWindow} for why the
     * two halves of the rate loop are not one window.
     */
    private final DetectionRateWindow detectionRate;

    /**
     * Chooses the rate {@link #sampleIntervalNanos} schedules deadlines at
     * (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R2). Built here rather than injected for the same
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

    /**
     * The most recently arrived frame <b>before</b> {@link #overlayIfNeeded} runs (docs/plans/done/CV-TRAINING-PLAN.md
     * §2/§D) — a sibling snapshot to {@link #latestFrame}, kept purely additively: written once per
     * {@link #onNext}, alongside {@link #latestFrame}, and read only by {@link #latestRawFrame()}. No
     * other behavior in this class reads or depends on it, so it cannot perturb the publish/detect
     * path. Training capture wants clean, un-annotated, full-resolution pixels — {@link #latestFrame}
     * may carry burned-in detection boxes/OSD when overlay rendering is configured, which would
     * contaminate a captured training image.
     */
    private volatile VideoFrame latestRawFrame;

    // Only ever touched from within onNext(), which Flow.Subscriber's contract serializes
    // (signals are never delivered concurrently) -- a plain (non-volatile) boolean latch is
    // enough, exactly like the frame-cadence fields below. Suppresses repeated WARNING logs for
    // a renderer that keeps throwing, without needing outage/backoff machinery: overlay failures
    // are cosmetic, not a resilience concern like detection failures are.
    private boolean overlayFailureLogged = false;

    // Same latch idiom as overlayFailureLogged above, for a throwing telemetrySupplier: reading a
    // telemetry sample for OSD burn-in is likewise cosmetic, never a resilience concern.
    private boolean telemetrySupplierFailureLogged = false;

    // Frame-arrival cadence measurement state. Only ever touched from within
    // onNext(), which Flow.Subscriber's contract serializes (signals are
    // never delivered concurrently) -- volatile purely for cross-thread
    // *visibility* between successive onNext calls, not for mutual exclusion.
    private volatile long lastFrameArrivalNanos = -1L;
    private volatile long framesObserved = 0L;
    private volatile double measuredFps = -1.0;

    // Deadline-based sampling state (docs/plans/active/CV-RATE-CONTROL-PLAN.md wave R1), replacing the
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

    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, null);
    }

    /**
     * Same as the 8-argument constructor, plus an {@link OverlayPort} collaborator.
     *
     * @param overlayPort nullable — {@code null} (the other constructor's default) means overlay
     *                     rendering never runs and every frame is published raw, exactly as
     *                     before this collaborator existed; the caller ({@link
     *                     DefaultStreamService}) follows the same nullable-collaborator
     *                     convention as its own {@code usageTracker}.
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher, OverlayPort overlayPort) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, null);
    }

    /**
     * Same as the 9-argument constructor, plus a {@link DetectionEventEngine} collaborator
     * (docs/plans/done/MVP2-PLAN.md §E, E-a) fed every completed detection result alongside {@link
     * #extrapolator}.
     *
     * @param eventEngine nullable — {@code null} (the other constructors' default) means no
     *                     debounced {@code DetectionEvent} tracking runs for this pipeline, the
     *                     same nullable-collaborator convention as {@code overlayPort}/{@code
     *                     usageTracker}. Deliberately a single bundled collaborator rather than
     *                     three more raw ports (asset/usage/event-store) on this constructor —
     *                     see {@code DefaultStreamService}'s wiring for why.
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher, OverlayPort overlayPort,
                           DetectionEventEngine eventEngine) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, null, null);
    }

    /**
     * Same as the 10-argument constructor, plus the collaborators needed to announce completed
     * detection results as live updates (docs/plans/done/REALTIME-PLAN.md §4).
     *
     * @param assetId                 nullable — the owning asset of the device streaming, resolved
     *                                 once by {@link DefaultStreamService} at stream start;
     *                                 {@code null} when the device belongs to no asset, in which
     *                                 case nothing is ever announced (mirrors {@code
     *                                 UsageTracker}'s own "untracked device" convention)
     * @param liveUpdatePublisherPort nullable — {@code null} (every other constructor's default)
     *                                 means no live-update announcements for this pipeline, the
     *                                 same nullable-collaborator convention as {@code
     *                                 overlayPort}/{@code eventEngine}
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher, OverlayPort overlayPort,
                           DetectionEventEngine eventEngine, AssetId assetId,
                           LiveUpdatePublisherPort liveUpdatePublisherPort) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, null);
    }

    /**
     * Same as the 12-argument constructor, plus a {@link Supplier} of the telemetry sample to burn
     * into the OSD (see the class javadoc's "Overlay burn-in" section).
     *
     * @param telemetrySupplier nullable — {@code null} (every other constructor's default) means
     *                           {@link PipelineConfig#overlayTelemetry()}'s OSD gate can never
     *                           activate for this pipeline, exactly as before this constructor
     *                           existed. When given, it is called at most once per published frame
     *                           (only when {@link #overlayPort} is configured and {@code
     *                           overlayTelemetry()} is {@code true}) and must therefore be cheap —
     *                           an in-memory read of the freshest known sample, never blocking I/O.
     *                           A {@code null} result (or a thrown exception, caught and treated the
     *                           same way) means "no sample right now," not a failure.
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                           Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                           StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                           EventPublisherPort eventPublisher, OverlayPort overlayPort,
                           DetectionEventEngine eventEngine, AssetId assetId,
                           LiveUpdatePublisherPort liveUpdatePublisherPort, Supplier<Telemetry> telemetrySupplier) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                System::nanoTime, StreamPipelineSettings.defaults());
    }

    /**
     * Test seam: same as the public constructors but with an injectable
     * nanotime source for the frame-cadence measurement and the detection
     * outage/backoff timing, so tests can drive both off a deterministic
     * synthetic clock instead of depending on real wall-clock timing.
     * Defaults {@link StreamPipelineSettings} to {@link StreamPipelineSettings#defaults()}.
     */
    StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort,
                   Supplier<Telemetry> telemetrySupplier, LongSupplier nanoTimeSource) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                nanoTimeSource, StreamPipelineSettings.defaults());
    }

    /**
     * Test/wiring seam: same as the 14-argument constructor, plus an explicit {@link
     * StreamPipelineSettings} (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3 config extraction) instead
     * of relying on {@link StreamPipelineSettings#defaults()} — lets a test drive the frame-cadence/
     * detection-backoff/extrapolation tuning deterministically, and lets {@link
     * DefaultStreamService} (wiring) supply a {@code vision-app}-bound configuration instead of this
     * class hardcoding one. Public — unlike the other test seams here — because {@code
     * DefaultStreamService} lives in a different feature package ({@code stream}, not {@code
     * pipeline}) and calls this constructor directly with its own resolved {@code nanoTimeSource}/
     * {@code settings} rather than the {@code System::nanoTime}/{@code
     * StreamPipelineSettings#defaults()} the shorter public constructors default to.
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort,
                   Supplier<Telemetry> telemetrySupplier, LongSupplier nanoTimeSource,
                   StreamPipelineSettings settings) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                nanoTimeSource, settings, System::nanoTime);
    }

    /**
     * Wiring seam: same as the 15-argument constructor, plus an optional pull-mode detection driver
     * (docs/plans/active/MEDIA-SOT-PLAN.md wave M5, D5/D6) — see {@link PullDetectionBinding}. Public, for the
     * same reason the 15-argument constructor is: {@code DefaultStreamService} supplies its own
     * resolved collaborators from a different feature package.
     *
     * @param pullDetection nullable — {@code null} (every other constructor's default) means push-mode
     *                      detection exactly as before this capability existed: {@link #maybeDetect}
     *                      samples frames and calls {@code detectionPort} directly, unchanged. When
     *                      given, this pipeline instead subscribes to {@link
     *                      PullDetectionBinding#results()} and forwards every arriving {@link
     *                      DetectionResult} to the same {@link #onDetectionResult} fan-out push mode
     *                      uses — push-mode sampling/submission never runs for this pipeline (see
     *                      {@link #maybeDetect}'s guard).
     */
    public StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort,
                   Supplier<Telemetry> telemetrySupplier, LongSupplier nanoTimeSource,
                   StreamPipelineSettings settings, PullDetectionBinding pullDetection) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                nanoTimeSource, settings, System::nanoTime, pullDetection);
    }

    /**
     * Package-private seam adding {@code latencyNanoSource} — see {@link #latencyNanoSource} for why
     * it is separate from {@code nanoTimeSource}. Only the same-package latency test injects it.
     * Delegates to the 17-argument master constructor with {@code pullDetection=null} (push mode).
     */
    StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort,
                   Supplier<Telemetry> telemetrySupplier, LongSupplier nanoTimeSource,
                   StreamPipelineSettings settings, LongSupplier latencyNanoSource) {
        this(streamId, device, config, source, detectionPort, streamPublisherPort, detectionRepositoryPort,
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, telemetrySupplier,
                nanoTimeSource, settings, latencyNanoSource, null);
    }

    /**
     * Master constructor: same as the 16-argument (latency-seam) constructor, plus {@code
     * pullDetection} — see the public 16-argument (settings + pullDetection) constructor's own javadoc.
     */
    StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort,
                   Supplier<Telemetry> telemetrySupplier, LongSupplier nanoTimeSource,
                   StreamPipelineSettings settings, LongSupplier latencyNanoSource,
                   PullDetectionBinding pullDetection) {
        this.latencyNanoSource = Objects.requireNonNull(latencyNanoSource, "latencyNanoSource must not be null");
        this.streamId = Objects.requireNonNull(streamId, "streamId must not be null");
        this.device = Objects.requireNonNull(device, "device must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.detectionPort = Objects.requireNonNull(detectionPort, "detectionPort must not be null");
        this.streamPublisherPort = Objects.requireNonNull(streamPublisherPort, "streamPublisherPort must not be null");
        this.detectionRepositoryPort =
                Objects.requireNonNull(detectionRepositoryPort, "detectionRepositoryPort must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.overlayPort = overlayPort; // nullable: no-op overlay rendering when absent
        this.eventEngine = eventEngine; // nullable: no detection-event tracking when absent
        this.assetId = assetId; // nullable: no owning asset, or live updates not wired
        this.liveUpdatePublisherPort = liveUpdatePublisherPort; // nullable: no live-update announcements when absent
        this.telemetrySupplier = telemetrySupplier; // nullable: no telemetry-OSD input when absent
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.pullDetection = pullDetection; // nullable: push-mode detection when absent (D5/D6)
        Objects.requireNonNull(settings, "settings must not be null");
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
        this.pipelineLatency = new PipelineLatencyWindow(settings.trackingStatsWindow());
        this.detectionRate = new DetectionRateWindow(settings.trackingStatsWindow(),
                pullDetection == null ? DetectionRateWindow.Transport.PUSH : DetectionRateWindow.Transport.PULL);
        this.rateController = new DetectionRateController(settings.adaptiveRate(), settings.cameraHfovDegrees());
        this.backoffNanos = this.detectionBackoffInitialNanos;
    }

    /**
     * Signals {@link StreamPublisherPort#streamStarted} and subscribes to the
     * source publisher, beginning frame processing — plus, in pull mode
     * (docs/plans/active/MEDIA-SOT-PLAN.md wave M5), subscribing {@link PullResultSubscriber} to {@link
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
     * bookkeeping — {@link #extrapolator} (via {@link DetectionExtrapolator#reset()}) and {@link
     * #latestDetections} — so no stale detection produced by the old model lingers (extrapolated
     * against, persisted, or shown) past the swap; {@link #latestDetections()} reads empty again
     * until the new model's first result completes. The very next sampled frame's {@link
     * #detectionPort}{@code .detect} call already carries {@code next} — including the new model —
     * since {@link DetectionPort}'s own contract runs inference "using the model ... in config" on
     * every call.
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
     * @param next the config to switch to
     */
    public void updateConfig(PipelineConfig next) {
        Objects.requireNonNull(next, "next must not be null");
        boolean modelChanged = !config.model().id().equals(next.model().id());
        config = next;
        if (modelChanged) {
            extrapolator.reset();
            latestDetections = List.of();
            // Same reason as the extrapolator: track ids and duty-cycle counters describe the model
            // that produced them, so carrying either across a swap would attribute one model's
            // objects and CPU to another's. A tracking-config change (mode, engine, cadences, lock)
            // deliberately clears nothing -- tracking is a hot knob like confidence and fps.
            trackBook.clear();
            trackingStats.clear();
            pipelineLatency.clear();
            detectionRate.clear();
            rateController.clear();
        }
        // docs/plans/active/MEDIA-SOT-PLAN.md wave M5, item 7: PATCH .../config keeps working in pull mode -- its
        // fields travel on the next PullControl via reconfigure() instead of the next FrameRequest,
        // since there is no per-frame outbound call in pull mode to carry them on.
        if (pullDetection != null) {
            pullDetection.port().reconfigure(streamId, next);
        }
    }

    /**
     * @return the most recently completed detection result's detections, or
     *         an empty list if no inference has completed yet. Updated for
     *         every completed inference, including empty results, so callers
     *         never see stale boxes past the point a tracked object
     *         disappeared. Left untouched while a detection outage withholds
     *         frames from the detector, since no inference actually ran.
     *         This is the raw, un-extrapolated result — the REST-facing
     *         surface; overlay burn-in renders {@link #extrapolator}'s
     *         smoothed boxes instead (docs/main/CYCLES-PLAN.md §12, CP-c), not
     *         this method's output.
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
     * @return this stream's tracking flow over the stats window (docs/plans/done/TRACKING-PLAN.md &sect;4.E) —
     *         detector passes, tracker frames, duty ratio, tracker-latency percentiles, the last
     *         detector reason, the confirmed lock and a state histogram — stamped with the tracking
     *         mode currently configured. {@link TrackingStats#empty} until the first result carrying
     *         {@link com.drones.vision.domain.model.TrackingTelemetry} arrives. Never {@code null}.
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
     *         (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1) — the source rate, the targeted rate, the
     *         achieved rate, and which of the three losses accounts for any difference. The
     *         companion to {@link #pipelineLatency()}: that one says what a detection cost, this one
     *         says how many were asked for and what became of them. Never {@code null}.
     */
    public DetectionRate detectionRate() {
        return detectionRate.snapshot(sourceFps(), targetFps(), rateController.demandFps());
    }

    /**
     * @return the most recently published frame — post-overlay burn-in when one was drawn, exactly
     *         the instance handed to {@link StreamPublisherPort#publish} (docs/plans/done/MVP3-PLAN.md C-a) —
     *         or {@link Optional#empty()} before the first frame has published. A latest-wins
     *         reference swap, same single-{@code volatile}-field convention as {@link
     *         #latestDetections()}: no copy per frame, no buffering, and safe to read from any
     *         thread (e.g. the HTTP thread serving a snapshot request) concurrently with {@link
     *         #onNext}.
     */
    public Optional<VideoFrame> latestFrame() {
        return Optional.ofNullable(latestFrame);
    }

    /**
     * @return the most recently arrived frame exactly as the source produced it — before overlay
     *         burn-in, at full resolution (docs/plans/done/CV-TRAINING-PLAN.md §2/§D), or {@link
     *         Optional#empty()} before the first frame has arrived. Backs training-sample capture,
     *         which wants clean pixels to label, never the (possibly overlay-rendered) frame {@link
     *         #latestFrame()} exposes. Same latest-wins, no-copy, any-thread-safe convention as
     *         {@link #latestFrame()}.
     */
    public Optional<VideoFrame> latestRawFrame() {
        return Optional.ofNullable(latestRawFrame);
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
            latestRawFrame = frame;
            VideoFrame published = overlayIfNeeded(frame);
            latestFrame = published;
            streamPublisherPort.publish(streamId, published);
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
     * Renders {@code frame} through {@link #overlayPort} when one is configured, {@link
     * PipelineConfig#overlayBurnIn()} is {@code true}, and either {@link #extrapolator}'s boxes at
     * {@code frame}'s capture time or {@link #telemetrySampleFor()} have something to draw,
     * returning the raw {@code frame} otherwise (no overlay configured, burn-in disabled, or
     * nothing to draw at all yet). Detection is always run against the raw {@code frame}, never the
     * rendered one — overlay is purely a publish-time presentation concern.
     *
     * <p><b>What {@code overlayBurnIn=false} actually skips</b> (docs/plans/done/MVP2-PLAN.md §V, V-e): the
     * {@link #extrapolator}{@code .at(...)}/{@link #telemetrySampleFor()} lookups below, {@link
     * OverlayPort#render}'s Java2D work (decode/allocate a fresh image, draw boxes/OSD, re-encode),
     * and the extra {@link VideoFrame} instance {@code render} returns — every publish falls
     * straight through to the raw, already-decoded frame. This is the pipeline's only burn-in
     * decision point, gating telemetry-OSD burn-in identically to detection-box burn-in. What it
     * does <b>not</b> skip: {@link #extrapolator}{@code .accept} (called from {@link
     * #onDetectionResult}, independent of this method) keeps running either way — it is cheap
     * (bookkeeping over at most two results) and turning it off per-config would only save that
     * bookkeeping, not the Java2D/copy cost this flag exists to avoid.
     *
     * <p>The detections passed to the renderer are {@link #extrapolator}'s output at {@code
     * frame.capturedAt()} (docs/main/CYCLES-PLAN.md &sect;12, CP-c), not the raw {@link
     * #latestDetections}, so burned-in boxes track between completed inferences instead of jumping
     * — same source-timestamp timebase as {@code DetectionResult.capturedAt}, never mixed with wall
     * clock. {@link #latestDetections()} (the REST-facing surface) is unaffected — it always
     * returns the raw latest result. The telemetry sample comes from {@link #telemetrySampleFor()}
     * — see that method's own javadoc for its own failure-handling.
     *
     * <p>A renderer exception is swallowed: overlay is cosmetic and must never be able to disrupt
     * the video path. The raw frame is published in that case, and at most one {@code WARNING} is
     * logged per run of failures (a simple boolean latch, reset the next time rendering
     * succeeds) — never per frame — so a persistently broken renderer doesn't spam logs.
     */
    private VideoFrame overlayIfNeeded(VideoFrame frame) {
        if (overlayPort == null || !config.overlayBurnIn()) {
            return frame;
        }
        List<Detection> detections = extrapolator.at(frame.capturedAt());
        Telemetry telemetry = telemetrySampleFor();
        if (detections.isEmpty() && telemetry == null) {
            return frame;
        }
        try {
            VideoFrame rendered = overlayPort.render(new AnnotatedFrame(frame, detections, telemetry));
            overlayFailureLogged = false;
            return rendered;
        } catch (RuntimeException e) {
            if (!overlayFailureLogged) {
                overlayFailureLogged = true;
                LOG.log(System.Logger.Level.WARNING, () -> "stream " + streamId.value()
                        + " overlay rendering failed, publishing raw frames until it recovers: " + e.getMessage());
            }
            return frame;
        }
    }

    /**
     * Resolves the telemetry sample to burn into this frame's OSD ({@link
     * PipelineConfig#overlayTelemetry()}), or {@code null} when it cannot/should not run: {@link
     * #config}{@code .overlayTelemetry()} is {@code false}, or no {@link #telemetrySupplier} was
     * configured (both mirror how {@link #overlayIfNeeded} itself is skipped when {@link
     * #overlayPort} is absent — this method is only ever called from there, once per published
     * frame, so {@link #telemetrySupplier} must be cheap, an in-memory read, never blocking I/O).
     *
     * <p>A supplier that throws is treated exactly like "no sample right now," never a pipeline
     * failure — telemetry burn-in is cosmetic, same as overlay rendering itself. At most one {@code
     * WARNING} is logged per run of failures (a boolean latch, reset the next time the supplier
     * succeeds, mirroring {@link #overlayFailureLogged}'s own throttling) so a persistently broken
     * supplier never spams logs on every frame.
     */
    private Telemetry telemetrySampleFor() {
        if (!config.overlayTelemetry()) {
            return null;
        }
        return readTelemetry();
    }

    /**
     * This frame's camera attitude for ego-motion compensation, or {@code null} when none can be
     * built (docs/conclusions/CV-RATE-BUDGET.md &sect;5, gap 3).
     *
     * <p>Short-circuits on an unconfigured field of view <b>before</b> touching the supplier: with
     * no optics described the attitude could not drive compensation anyway, so the default
     * deployment pays nothing at all for this feature.
     *
     * <p>Deliberately not routed through {@link #telemetrySampleFor()}: that one is gated on {@link
     * PipelineConfig#overlayTelemetry()}, which is a <i>presentation</i> switch. Turning the OSD off
     * must not silently disable ego-motion compensation — they are unrelated concerns that happen to
     * read the same supplier.
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
                        + " telemetry supplier failed, publishing without an OSD sample until it recovers: "
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
     * (docs/plans/active/CV-RATE-CONTROL-PLAN.md &sect;1, losses L1/L2).
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
     * Gated first — before the outage/in-flight logic below — on {@link
     * PipelineConfig#detectionEnabled()} (docs/plans/done/CV-CONTROL-PLAN.md &sect;1, &sect;A): {@code false}
     * returns immediately, so a disabled stream spends zero CPU on inference <i>and</i> stops
     * probing during an outage too — nothing below this check ever runs. Re-enabling resumes on the
     * next sampled frame, exactly where the (frozen, untouched) outage/backoff state left off.
     *
     * <p>Also gated on {@link #pullDetection} being absent (docs/plans/active/MEDIA-SOT-PLAN.md wave M5): in
     * pull mode the worker runs its own (ported) deadline sampler and decides when to detect, so this
     * pipeline's own sampler/in-flight bound/{@link #detectionPort} submission never runs — {@link
     * PullResultSubscriber} is the whole of pull-mode detection. This is the one line push mode's own
     * behavior depends on being a no-op for: {@code pullDetection} is {@code null} for every existing
     * caller, so the check below always falls through exactly as it did before this capability existed.
     */
    private void maybeDetect(VideoFrame frame, long now) {
        if (!config.detectionEnabled() || pullDetection != null) {
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
     * Fans out one completed result — after enforcing {@link PipelineConfig#labelFilter()} exactly
     * once, centrally, here (docs/plans/done/CV-CONTROL-PLAN.md &sect;A, the dormant-field fix) — to every
     * downstream consumer: {@link #latestDetections()}, {@link #extrapolator} (and therefore
     * overlay burn-in), {@link #trackBook}, {@link #trackingStats}, {@link #eventEngine}, {@link
     * #liveUpdatePublisherPort}, and persistence/the {@code DETECTION} event. Filtering once here,
     * before any of those, is what makes every consumer see the same filtered set uniformly instead
     * of each having to know about {@code labelFilter} itself.
     *
     * <p>This list is a <b>fan-out of consumers by design</b>: adding one is not a new
     * responsibility for this class (TRACKING-ORCHESTRATION.md &sect;2.3). The tracking work
     * genuinely lives in {@link TrackBook}/{@link TrackingStatsWindow}, carved as peers of {@link
     * DetectionExtrapolator} so the decomposition this class is queued for inherits well-shaped
     * perception stages rather than a fatter method.
     */
    private void onDetectionResult(DetectionResult result) {
        DetectionResult filtered = applyLabelFilter(result);
        latestDetections = filtered.detections();
        extrapolator.accept(filtered);
        trackBook.accept(filtered);
        trackingStats.accept(filtered);
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
     * Drops every detection whose label is not in {@link PipelineConfig#labelFilter()} — an empty
     * filter keeps everything, the same semantics an empty filter already has on {@link
     * PipelineConfig} itself. Returns {@code result} unchanged (same instance) when nothing was
     * actually dropped, so the common case (no filter configured, or every detection already
     * matches) allocates nothing new.
     *
     * <p>The rebuilt result carries {@link DetectionResult#tracking()} through unchanged: a label
     * filter drops <i>detections</i>, and per-frame tracking facts (whether the detector ran, why,
     * what the tracker cost, which track is locked) are true of the frame regardless of which of its
     * boxes survived filtering. Dropping them here would silently zero the duty-cycle stats of every
     * stream that happens to use a label filter.
     */
    private DetectionResult applyLabelFilter(DetectionResult result) {
        Set<String> labelFilter = config.labelFilter();
        if (labelFilter.isEmpty()) {
            return result;
        }
        List<Detection> kept = result.detections().stream().filter(d -> labelFilter.contains(d.label())).toList();
        if (kept.size() == result.detections().size()) {
            return result;
        }
        return new DetectionResult(result.streamId(), result.frameSequence(), result.capturedAt(), kept,
                result.inferenceLatency(), result.tracking());
    }

    /**
     * Folds a pull result's worker-reported diagnostics into {@link #detectionRate}/{@link
     * #pipelineLatency}/{@link #rateController} — the pull-mode analogue of what {@link
     * #submitDetection}'s completion callback does for push mode (docs/plans/active/MEDIA-SOT-PLAN.md &sect;7).
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
     * Pull-mode detection driver (docs/plans/active/MEDIA-SOT-PLAN.md wave M5, D5/D6): subscribes to {@link
     * #pullDetection}'s live result publisher and forwards every arriving {@link DetectionResult} to
     * the same {@link #onDetectionResult} fan-out push mode uses, after folding its worker-reported
     * diagnostics into the rate/latency windows ({@link #recordPullTelemetry}). Requests one item at a
     * time, mirroring this pipeline's own video-path backpressure discipline ({@link #onSubscribe}).
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
            recordPullTelemetry(result);
            onDetectionResult(result);
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
