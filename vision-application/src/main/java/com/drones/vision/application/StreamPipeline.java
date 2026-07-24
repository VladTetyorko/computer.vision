package com.drones.vision.application;

import com.drones.vision.domain.model.AnnotatedFrame;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.Detection;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.Event;
import com.drones.vision.domain.model.EventType;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.VideoFrame;
import com.drones.vision.domain.port.out.DetectionPort;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;
import com.drones.vision.domain.port.out.EventPublisherPort;
import com.drones.vision.domain.port.out.LiveUpdatePublisherPort;
import com.drones.vision.domain.port.out.OverlayPort;
import com.drones.vision.domain.port.out.StreamPublisherPort;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
 *   <li><b>Overlay burn-in</b> (docs/MVP1-PLAN.md §C8, smoothed per
 *       docs/CYCLES-PLAN.md §12 CP-c; optional per docs/MVP2-PLAN.md §V,
 *       V-e): when an {@link OverlayPort} is configured (constructor
 *       argument, nullable — {@code null} keeps today's raw-publish behavior
 *       everywhere) and {@link PipelineConfig#overlayBurnIn()} is {@code
 *       true} (the default) and {@link
 *       #extrapolator}'s boxes at this frame's capture time are non-empty,
 *       each frame is rendered through {@link OverlayPort#render} — as an
 *       {@link AnnotatedFrame} carrying the extrapolated detections and a
 *       {@code null} telemetry sample, since this pipeline has no telemetry
 *       input yet — before being published. The extrapolator tracks the two
 *       most recently completed results and, between them, moves each
 *       matched box toward where it is predicted to be at the publishing
 *       frame's timestamp instead of freezing it at its last detected
 *       position — see {@link DetectionExtrapolator} for the matching/
 *       velocity/cap details; {@link #latestDetections()} (the REST-facing
 *       surface) is unaffected, it always returns the raw latest result.
 *       {@link PipelineConfig#overlayTelemetry()}'s telemetry-OSD gate
 *       cannot activate until a later task plumbs a telemetry input into
 *       this class, so today only detections-only burn-in ships. Without an
 *       {@code OverlayPort} (the default) or before any detection has
 *       completed, the raw frame is published unchanged, exactly as before
 *       this feature. A renderer that throws is treated as a purely
 *       cosmetic failure, never a pipeline failure: the raw frame is
 *       published instead, and at most one {@code WARNING} is logged per
 *       failure run (a boolean latch, reset the next time rendering
 *       succeeds) so a persistently broken renderer never spams logs on
 *       every frame.</li>
 * </ul>
 *
 * <h2>Inference sampling</h2>
 * Every Nth frame is sampled for inference, where N is derived from {@link
 * PipelineConfig#inferenceFps()} and the source's <b>measured</b> arrival
 * rate: each frame's inter-arrival delta (from an injectable {@link
 * LongSupplier} nanotime source, defaulting to {@link System#nanoTime()}) is
 * folded into an exponentially-weighted moving average (EWMA, {@value
 * #MEASURED_FPS_EWMA_ALPHA}) of the source frame rate. Until {@value
 * #WARMUP_FRAMES} frames have arrived, the measurement is not yet trusted and
 * a {@value #ASSUMED_SOURCE_FPS} fps assumption is used instead; the measured
 * rate is sanity-clamped to {@code [}{@value #MIN_MEASURED_FPS}{@code ,}
 * {@value #MAX_MEASURED_FPS}{@code ]} fps to guard against a stalled/degenerate
 * clock. The sampling interval ({@code everyNth = max(1, round(effectiveFps /
 * inferenceFps))}) is recomputed on every frame as the measurement updates,
 * so it tracks a source whose actual rate differs from — or drifts away from
 * — the 30fps assumption. A sampled frame is only submitted to {@link
 * DetectionPort#detect} if fewer than {@link
 * PipelineConfig#maxInFlightInferences()} calls are currently in flight;
 * otherwise it is skipped — never queued — so a slow CV service can never
 * stall the video path.
 *
 * <p>The most recently completed detection result is kept in a {@code
 * volatile} field ({@link #latestDetections()}); it is persisted via {@link
 * DetectionRepositoryPort} and announced via a {@code DETECTION} {@link
 * Event} only when non-empty, so uneventful frames don't spam storage/events.
 *
 * <p>The most recently <b>published</b> frame is likewise kept in a {@code volatile} field ({@link
 * #latestFrame()}, docs/MVP3-PLAN.md C-a) — the same instance {@link StreamPublisherPort#publish}
 * was just handed (post-overlay burn-in when one was drawn), a latest-wins reference swap with no
 * per-frame copy. This is what backs the manager dashboard's per-stream JPEG snapshot endpoint.
 *
 * <p><b>Debounced detection events</b> (docs/MVP2-PLAN.md §E, E-a): every completed result —
 * empty or not — also feeds an optional {@link DetectionEventEngine} ({@code eventEngine},
 * nullable, same convention as {@code overlayPort}), which collapses a tracked label's
 * consecutive-qualifying-results streak into an open/close {@code DetectionEvent} lifecycle. This
 * is a separate concern from {@link #latestDetections()}/overlay burn-in: the engine only ever
 * reads results, it never influences what gets published or returned from this class.
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

    /** Assumed source frame rate used before the measured rate is trusted (see {@link #WARMUP_FRAMES}). */
    static final int ASSUMED_SOURCE_FPS = 30;

    /** Smoothing factor for the source frame-rate EWMA; lower = smoother/slower to react. */
    static final double MEASURED_FPS_EWMA_ALPHA = 0.2;

    /** Number of frame arrivals observed before the measured rate replaces {@link #ASSUMED_SOURCE_FPS}. */
    static final int WARMUP_FRAMES = 5;

    /** Sanity clamp bounds for the measured source frame rate. */
    static final double MIN_MEASURED_FPS = 1.0;
    static final double MAX_MEASURED_FPS = 240.0;

    /** Backoff before the first retry probe after a detection outage begins (1s). */
    static final long INITIAL_BACKOFF_NANOS = 1_000_000_000L;

    /** Cap the exponential backoff doubles up to while an outage's probes keep failing (10s). */
    static final long MAX_BACKOFF_NANOS = 10_000_000_000L;

    private static final System.Logger LOG = System.getLogger(StreamPipeline.class.getName());

    private final StreamId streamId;
    private final Device device;
    private final PipelineConfig config;
    private final Flow.Publisher<VideoFrame> source;
    private final DetectionPort detectionPort;
    private final StreamPublisherPort streamPublisherPort;
    private final DetectionRepositoryPort detectionRepositoryPort;
    private final EventPublisherPort eventPublisher;
    private final OverlayPort overlayPort;
    private final DetectionEventEngine eventEngine;
    private final AssetId assetId;
    private final LiveUpdatePublisherPort liveUpdatePublisherPort;
    private final LongSupplier nanoTimeSource;
    private final DetectionExtrapolator extrapolator = new DetectionExtrapolator();

    private final AtomicInteger inFlightInferences = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile Flow.Subscription subscription;
    private volatile List<Detection> latestDetections = List.of();
    private volatile VideoFrame latestFrame;

    // Only ever touched from within onNext(), which Flow.Subscriber's contract serializes
    // (signals are never delivered concurrently) -- a plain (non-volatile) boolean latch is
    // enough, exactly like the frame-cadence fields below. Suppresses repeated WARNING logs for
    // a renderer that keeps throwing, without needing outage/backoff machinery: overlay failures
    // are cosmetic, not a resilience concern like detection failures are.
    private boolean overlayFailureLogged = false;

    // Frame-arrival cadence measurement state. Only ever touched from within
    // onNext(), which Flow.Subscriber's contract serializes (signals are
    // never delivered concurrently) -- volatile purely for cross-thread
    // *visibility* between successive onNext calls, not for mutual exclusion.
    private volatile long lastFrameArrivalNanos = -1L;
    private volatile long framesObserved = 0L;
    private volatile double measuredFps = -1.0;
    private volatile long sampleEveryNthFrame;

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
    private long backoffNanos = INITIAL_BACKOFF_NANOS;
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
     * (docs/MVP2-PLAN.md §E, E-a) fed every completed detection result alongside {@link
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
     * detection results as live updates (docs/REALTIME-PLAN.md §4).
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
                eventPublisher, overlayPort, eventEngine, assetId, liveUpdatePublisherPort, System::nanoTime);
    }

    /**
     * Test seam: same as the public constructors but with an injectable
     * nanotime source for the frame-cadence measurement and the detection
     * outage/backoff timing, so tests can drive both off a deterministic
     * synthetic clock instead of depending on real wall-clock timing.
     */
    StreamPipeline(StreamId streamId, Device device, PipelineConfig config,
                   Flow.Publisher<VideoFrame> source, DetectionPort detectionPort,
                   StreamPublisherPort streamPublisherPort, DetectionRepositoryPort detectionRepositoryPort,
                   EventPublisherPort eventPublisher, OverlayPort overlayPort, DetectionEventEngine eventEngine,
                   AssetId assetId, LiveUpdatePublisherPort liveUpdatePublisherPort, LongSupplier nanoTimeSource) {
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
        this.nanoTimeSource = Objects.requireNonNull(nanoTimeSource, "nanoTimeSource must not be null");
        this.sampleEveryNthFrame = everyNth(ASSUMED_SOURCE_FPS);
    }

    /**
     * Signals {@link StreamPublisherPort#streamStarted} and subscribes to the
     * source publisher, beginning frame processing. Must be called exactly
     * once.
     */
    public void start() {
        streamPublisherPort.streamStarted(streamId, device);
        source.subscribe(this);
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
     *         smoothed boxes instead (docs/CYCLES-PLAN.md §12, CP-c), not
     *         this method's output.
     */
    public List<Detection> latestDetections() {
        return latestDetections;
    }

    /**
     * @return the most recently published frame — post-overlay burn-in when one was drawn, exactly
     *         the instance handed to {@link StreamPublisherPort#publish} (docs/MVP3-PLAN.md C-a) —
     *         or {@link Optional#empty()} before the first frame has published. A latest-wins
     *         reference swap, same single-{@code volatile}-field convention as {@link
     *         #latestDetections()}: no copy per frame, no buffering, and safe to read from any
     *         thread (e.g. the HTTP thread serving a snapshot request) concurrently with {@link
     *         #onNext}.
     */
    public Optional<VideoFrame> latestFrame() {
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
        recordArrivalAndRecomputeSampling();
        try {
            VideoFrame published = overlayIfNeeded(frame);
            latestFrame = published;
            streamPublisherPort.publish(streamId, published);
            if (frame.sequence() % sampleEveryNthFrame == 0) {
                maybeDetect(frame);
            }
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
     * PipelineConfig#overlayBurnIn()} is {@code true}, and {@link #extrapolator}'s boxes at {@code
     * frame}'s capture time have something to draw, returning the raw {@code frame} otherwise (no
     * overlay configured, burn-in disabled, or nothing detected yet). Detection is always run
     * against the raw {@code frame}, never the rendered one — overlay is purely a publish-time
     * presentation concern.
     *
     * <p><b>What {@code overlayBurnIn=false} actually skips</b> (docs/MVP2-PLAN.md §V, V-e): the
     * {@link #extrapolator}{@code .at(...)} lookup (matching/extrapolation math) below, {@link
     * OverlayPort#render}'s Java2D work (decode/allocate a fresh image, draw boxes/OSD, re-encode),
     * and the extra {@link VideoFrame} instance {@code render} returns — every publish falls
     * straight through to the raw, already-decoded frame. This is the pipeline's only burn-in
     * decision point, so it gates telemetry-OSD burn-in identically to detection-box burn-in once a
     * telemetry input exists here (today neither runs without a configured {@link #overlayPort}
     * regardless of this flag, since telemetry is always passed as {@code null} — see the class
     * javadoc). What it does <b>not</b> skip: {@link #extrapolator}{@code .accept} (called from
     * {@link #onDetectionResult}, independent of this method) keeps running either way — it is
     * cheap (bookkeeping over at most two results) and turning it off per-config would only save
     * that bookkeeping, not the Java2D/copy cost this flag exists to avoid.
     *
     * <p>The detections passed to the renderer are {@link #extrapolator}'s output at {@code
     * frame.capturedAt()} (docs/CYCLES-PLAN.md &sect;12, CP-c), not the raw {@link
     * #latestDetections}, so burned-in boxes track between completed inferences instead of jumping
     * — same source-timestamp timebase as {@code DetectionResult.capturedAt}, never mixed with wall
     * clock. {@link #latestDetections()} (the REST-facing surface) is unaffected — it always
     * returns the raw latest result.
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
        if (detections.isEmpty()) {
            return frame;
        }
        try {
            VideoFrame rendered = overlayPort.render(new AnnotatedFrame(frame, detections, null));
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
     * Folds this frame's arrival into the source frame-rate measurement and
     * recomputes {@link #sampleEveryNthFrame} from the result. Cannot throw
     * (deliberately kept outside the publish/detect try-catch below, which
     * only handles collaborator failures).
     */
    private void recordArrivalAndRecomputeSampling() {
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
                        : MEASURED_FPS_EWMA_ALPHA * instantaneousFps + (1 - MEASURED_FPS_EWMA_ALPHA) * measuredFps;
                measuredFps = clamp(blended, MIN_MEASURED_FPS, MAX_MEASURED_FPS);
            }
        }
        lastFrameArrivalNanos = now;

        boolean trustMeasurement = framesObserved >= WARMUP_FRAMES && measuredFps >= 0;
        sampleEveryNthFrame = everyNth(trustMeasurement ? measuredFps : ASSUMED_SOURCE_FPS);
    }

    private long everyNth(double effectiveFps) {
        return Math.max(1L, Math.round(effectiveFps / config.inferenceFps()));
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

    private void maybeDetect(VideoFrame frame) {
        switch (outageDecision()) {
            case SKIP -> {
                // still backing off, or a probe is already in flight: never counted as in-flight
            }
            case PROBE -> submitDetection(frame, true);
            case NORMAL -> {
                if (inFlightInferences.get() >= config.maxInFlightInferences()) {
                    return; // bounded in-flight: skip this sample rather than queue it
                }
                inFlightInferences.incrementAndGet();
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
        detectionPort.detect(frame, config).whenComplete((result, error) -> {
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
                backoffNanos = INITIAL_BACKOFF_NANOS;
                outageFailureCount = 0;
                nextProbeAtNanos = nanoTimeSource.getAsLong() + backoffNanos;
            } else {
                outageFailureCount++;
                if (isProbe) {
                    backoffNanos = Math.min(backoffNanos * 2, MAX_BACKOFF_NANOS);
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
            backoffNanos = INITIAL_BACKOFF_NANOS;
        }
        if (recovered) {
            LOG.log(System.Logger.Level.INFO, () -> "stream " + streamId.value() + " detection recovered after "
                    + failuresDuringOutage + " failed attempt(s) during the outage");
        }
        onDetectionResult(result);
    }

    private void onDetectionResult(DetectionResult result) {
        latestDetections = result.detections();
        extrapolator.accept(result);
        if (eventEngine != null) {
            eventEngine.accept(result);
        }
        if (liveUpdatePublisherPort != null && assetId != null) {
            liveUpdatePublisherPort.publishDetections(assetId, result);
        }
        if (!result.detections().isEmpty()) {
            detectionRepositoryPort.save(result);
            eventPublisher.publish(Event.of(streamId, EventType.DETECTION,
                    "Detected " + result.detections().size() + " object(s) on frame " + result.frameSequence()));
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
        streamPublisherPort.streamEnded(streamId);
    }

    private static Throwable unwrap(Throwable t) {
        return (t instanceof CompletionException && t.getCause() != null) ? t.getCause() : t;
    }
}
