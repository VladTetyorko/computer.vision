package com.drones.vision.perception.application.pipeline;

import java.time.Duration;

import com.drones.vision.perception.application.stream.TrackingConfigPatch;

/**
 * Tunables for one running stream's {@link StreamPipeline} runtime — frame-cadence measurement,
 * detection-outage backoff, video-source reopen backoff (the bounds {@link DefaultStreamService}
 * passes when wrapping a source in a {@link SupervisedPublisher}), and detection-box extrapolation
 * (see {@link DetectionExtrapolator}) — extracted per docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3's
 * config-extraction rule: none of these may live as a hardcoded literal inside the classes that use
 * them.
 *
 * <p>Framework-free (no Spring annotation): {@code vision-app} binds a {@code
 * VisionApplicationProperties} record from {@code application.yaml} and maps it onto this
 * record's constructor, one field at a time, before handing it to {@link DefaultStreamService}.
 * Every {@link #defaults()} value is byte-identical to the literal it replaces.
 *
 * <p><b>{@link #trackingSeed()} is the one component the pipeline itself never reads</b> — it is the
 * deployment's tracking default for <b>new</b> streams, consumed by {@code
 * DefaultStreamService#start} and by nothing else. It lives here rather than at the REST edge so
 * that every start path (device, asset, simulation, demo fleet) seeds identically from one place
 * instead of the deployment default applying or not depending on which button the operator pressed;
 * it rides this record because this is already what {@code vision-app} maps {@code vision.tracking.*}
 * onto (the stats window and track retention below), so it needed no new collaborator anywhere.
 * Seeding a start is the only thing it can do: a running stream's tracking configuration is its own
 * state, changed only by {@code PATCH} (docs/extracts/TRACKING-ORCHESTRATION.md &sect;4.1).
 *
 * <p><b>Two distinct backoff pairs are preserved on purpose</b> (not unified): {@link
 * #detectionBackoffInitialNanos()}/{@link #detectionBackoffMaxNanos()} bound how quickly {@link
 * StreamPipeline} retries a probe inference after a detection outage (1s&ndash;10s); {@link
 * #sourceReopenBackoffInitialNanos()}/{@link #sourceReopenBackoffMaxNanos()} bound how quickly
 * {@link DefaultStreamService} retries reopening a failed video source (1s&ndash;30s, {@link
 * SupervisedPublisher}'s own historical default). The two concerns are unrelated failures with
 * unrelated recovery costs, so collapsing them to one pair would be a behavior change, not a
 * cleanup.
 *
 * @param assumedSourceFps               assumed source frame rate used before the measured rate is
 *                                        trusted; must be positive
 * @param measuredFpsEwmaAlpha            smoothing factor for the source frame-rate EWMA, in
 *                                        {@code (0,1]}; lower = smoother/slower to react
 * @param warmupFrames                   frame arrivals observed before the measured rate replaces
 *                                        {@code assumedSourceFps}; must be positive
 * @param minMeasuredFps                 lower sanity-clamp bound for the measured source frame rate
 * @param maxMeasuredFps                 upper sanity-clamp bound for the measured source frame
 *                                        rate; must exceed {@code minMeasuredFps}
 * @param detectionBackoffInitialNanos   backoff before the first retry probe after a detection
 *                                        outage begins; must be positive
 * @param detectionBackoffMaxNanos       cap the exponential detection-outage backoff doubles up to;
 *                                        must be &ge; {@code detectionBackoffInitialNanos}
 * @param sourceReopenBackoffInitialNanos backoff before the first retry after a video source ends;
 *                                        must be positive
 * @param sourceReopenBackoffMaxNanos     cap the exponential source-reopen backoff doubles up to;
 *                                        must be &ge; {@code sourceReopenBackoffInitialNanos}
 * @param extrapolationMaxMillis         how far past the latest completed result's capture time
 *                                        {@link DetectionExtrapolator} extrapolates before the
 *                                        output freezes; must not be negative
 * @param extrapolationMatchGate         max normalized box-center distance for a same-label match
 *                                        between two completed results, used only where at least
 *                                        one side is untracked; must not be negative
 * @param trackingStatsWindow            how far back {@link TrackingStatsWindow}'s counters reach
 *                                        (docs/plans/done/TRACKING-PLAN.md &sect;4.E); must be positive.
 *                                        {@code vision-app} binds this to {@code
 *                                        vision.tracking.stats-window-seconds}
 * @param trackRetention                 how long a track that stops arriving stays in {@link
 *                                        TrackBook} before being expired; must be positive
 * @param trackingSeed                   the deployment's tracking defaults for <b>new</b> streams
 *                                        ({@code vision.tracking.*} — docs/extracts/TRACKING-ORCHESTRATION.md
 *                                        &sect;4.1), read by {@code DefaultStreamService#start}
 *                                        alone; never {@code null}, use {@link
 *                                        TrackingConfigPatch#NOTHING} for "the deployment states
 *                                        nothing"
 * @param cameraHfovDegrees              the camera's horizontal field of view in degrees, the scale
 *                                        that turns an attitude delta into a frame-relative shift;
 *                                        must be in {@code [0, 180)}, where {@code 0} means the
 *                                        deployment has not described its optics
 * @param adaptiveRate                   whether and how far the sample rate may rise above {@code
 *                                        inferenceFps} when the tracked target is about to leave
 *                                        its association budget (docs/plans/active/CV-RATE-CONTROL-PLAN.md
 *                                        wave R2); never {@code null}, use {@link
 *                                        AdaptiveRateSettings#disabled()} to pin the rate
 * @param detectionDemandPollInterval    how often {@code DefaultStreamService}'s demand-poll task
 *                                        re-evaluates every running stream's {@code
 *                                        DetectionDemandPort} (docs/plans/active/CV-DEMAND-PLAN.md &sect;3.3,
 *                                        &sect;3.4); must be positive. Irrelevant, and the task never even
 *                                        scheduled, when no port is wired
 * @param detectionDemandGrace           how long a stream keeps detecting after its last observed
 *                                        demand before {@link StreamPipeline#updateDetectionDemand}
 *                                        is told {@code false} (docs/plans/active/CV-DEMAND-PLAN.md &sect;2,
 *                                        &sect;3.3) — the window that keeps navigating between pages from
 *                                        thrashing the detector on and off; must be positive
 */
public record StreamPipelineSettings(
        int assumedSourceFps,
        double measuredFpsEwmaAlpha,
        int warmupFrames,
        double minMeasuredFps,
        double maxMeasuredFps,
        long detectionBackoffInitialNanos,
        long detectionBackoffMaxNanos,
        long sourceReopenBackoffInitialNanos,
        long sourceReopenBackoffMaxNanos,
        long extrapolationMaxMillis,
        double extrapolationMatchGate,
        Duration trackingStatsWindow,
        Duration trackRetention,
        TrackingConfigPatch trackingSeed,
        double cameraHfovDegrees,
        AdaptiveRateSettings adaptiveRate,
        Duration detectionDemandPollInterval,
        Duration detectionDemandGrace,
        Duration videoStaleAfter) {

    /** @see #trackRetention() */
    private static final Duration DEFAULT_TRACK_RETENTION = Duration.ofSeconds(5);

    /** @see #detectionDemandPollInterval() */
    private static final Duration DEFAULT_DETECTION_DEMAND_POLL_INTERVAL = Duration.ofSeconds(2);

    /** @see #detectionDemandGrace() */
    private static final Duration DEFAULT_DETECTION_DEMAND_GRACE = Duration.ofSeconds(30);

    /**
     * How long a running stream may go without a frame before {@code StreamState} reports it
     * {@code STALLED} rather than {@code LIVE} (docs/plans/active/STREAM-STATE-PLAN.md &sect;2.4).
     *
     * <p>5s is unambiguous against the source rates this deployment actually runs (~28 fps measured)
     * — roughly 140 missed frames — while staying short enough that an operator sees a dead feed
     * called dead. <b>A deliberately slow source must raise it:</b> a 1 fps still camera is healthy
     * at a 1s gap but would read {@code STALLED} forever against a threshold tuned for video, which
     * is why this is a setting and not a constant.
     *
     * @see #videoStaleAfter()
     */
    private static final Duration DEFAULT_VIDEO_STALE_AFTER = Duration.ofSeconds(5);

    /**
     * {@code 0} = the deployment has not described its camera's optics, which disables pose-based
     * ego-motion compensation rather than inventing a scale for it (see {@code
     * CameraAttitude#known()}). Deliberately not a plausible-looking guess such as 60&deg;: a wrong
     * field of view produces a confidently wrong pixel shift, which is worse than no compensation at
     * all, and the flow-based compensator still runs meanwhile.
     *
     * @see #cameraHfovDegrees()
     */
    public static final double DEFAULT_CAMERA_HFOV_DEGREES = 0.0;

    /**
     * The canonical constructor before docs/plans/done/TRACKING-PLAN.md wave T3 added the two tracking
     * tunables, kept as a convenience constructor defaulting both to {@link #defaults()}'s values,
     * so every pre-existing call site — including {@code vision-app}'s own property mapping —
     * compiles unchanged. Same "N-1-arg convenience ctor" idiom the domain's {@code
     * PipelineConfig}/{@code Detection}/{@code DetectionResult} already use.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate,
                Duration.ofSeconds(TrackingStatsWindow.DEFAULT_WINDOW_SECONDS), DEFAULT_TRACK_RETENTION,
                TrackingConfigPatch.NOTHING);
    }

    /**
     * The canonical constructor before the tracking seed moved into this record, kept as a
     * convenience constructor defaulting it to {@link TrackingConfigPatch#NOTHING} — "the deployment
     * states nothing about tracking", which folds to exactly the domain's own literals. Same
     * "N-1-arg convenience ctor" idiom as above.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate, Duration trackingStatsWindow,
                                   Duration trackRetention) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate, trackingStatsWindow,
                trackRetention, TrackingConfigPatch.NOTHING);
    }

    /**
     * The canonical constructor before {@code adaptiveRate} was added, kept as a convenience
     * constructor defaulting it to {@link AdaptiveRateSettings#defaults()}. Same "N-1-arg
     * convenience ctor" idiom as the others here.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate, Duration trackingStatsWindow,
                                   Duration trackRetention, TrackingConfigPatch trackingSeed,
                                   double cameraHfovDegrees) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate, trackingStatsWindow,
                trackRetention, trackingSeed, cameraHfovDegrees, AdaptiveRateSettings.defaults());
    }

    /**
     * The canonical constructor before {@code cameraHfovDegrees} was added, kept as a convenience
     * constructor defaulting it to {@link #DEFAULT_CAMERA_HFOV_DEGREES} — "the deployment has not
     * described its optics", which disables pose compensation exactly as an absent value should.
     * Same "N-1-arg convenience ctor" idiom as the two above.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate, Duration trackingStatsWindow,
                                   Duration trackRetention, TrackingConfigPatch trackingSeed) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate, trackingStatsWindow,
                trackRetention, trackingSeed, DEFAULT_CAMERA_HFOV_DEGREES);
    }

    /**
     * The canonical constructor before docs/plans/active/CV-DEMAND-PLAN.md wave D1 added the two demand
     * tunables, kept as a convenience constructor defaulting both to {@link
     * #DEFAULT_DETECTION_DEMAND_POLL_INTERVAL}/{@link #DEFAULT_DETECTION_DEMAND_GRACE} — the values
     * the plan itself pins (2s/30s) — so every pre-existing call site, including {@code
     * DefaultStreamService}'s own {@code withSourceReopenBackoff} test seam, compiles unchanged.
     * Same "N-1-arg convenience ctor" idiom as every other addition to this record.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate, Duration trackingStatsWindow,
                                   Duration trackRetention, TrackingConfigPatch trackingSeed,
                                   double cameraHfovDegrees, AdaptiveRateSettings adaptiveRate) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate, trackingStatsWindow,
                trackRetention, trackingSeed, cameraHfovDegrees, adaptiveRate,
                DEFAULT_DETECTION_DEMAND_POLL_INTERVAL, DEFAULT_DETECTION_DEMAND_GRACE);
    }

    /**
     * The shape before {@link #videoStaleAfter()} was added (docs/plans/active/STREAM-STATE-PLAN.md
     * &sect;2.4), defaulting it to {@link #DEFAULT_VIDEO_STALE_AFTER} — the value the plan itself
     * pins (5s) — so every pre-existing call site compiles unchanged. Same "N-1-arg convenience
     * ctor" idiom as every other addition to this record.
     */
    public StreamPipelineSettings(int assumedSourceFps, double measuredFpsEwmaAlpha, int warmupFrames,
                                   double minMeasuredFps, double maxMeasuredFps, long detectionBackoffInitialNanos,
                                   long detectionBackoffMaxNanos, long sourceReopenBackoffInitialNanos,
                                   long sourceReopenBackoffMaxNanos, long extrapolationMaxMillis,
                                   double extrapolationMatchGate, Duration trackingStatsWindow,
                                   Duration trackRetention, TrackingConfigPatch trackingSeed,
                                   double cameraHfovDegrees, AdaptiveRateSettings adaptiveRate,
                                   Duration detectionDemandPollInterval, Duration detectionDemandGrace) {
        this(assumedSourceFps, measuredFpsEwmaAlpha, warmupFrames, minMeasuredFps, maxMeasuredFps,
                detectionBackoffInitialNanos, detectionBackoffMaxNanos, sourceReopenBackoffInitialNanos,
                sourceReopenBackoffMaxNanos, extrapolationMaxMillis, extrapolationMatchGate, trackingStatsWindow,
                trackRetention, trackingSeed, cameraHfovDegrees, adaptiveRate,
                detectionDemandPollInterval, detectionDemandGrace, DEFAULT_VIDEO_STALE_AFTER);
    }

    public StreamPipelineSettings {
        if (assumedSourceFps <= 0) {
            throw new IllegalArgumentException("assumedSourceFps must be positive, was " + assumedSourceFps);
        }
        if (measuredFpsEwmaAlpha <= 0 || measuredFpsEwmaAlpha > 1) {
            throw new IllegalArgumentException(
                    "measuredFpsEwmaAlpha must be in (0,1], was " + measuredFpsEwmaAlpha);
        }
        if (warmupFrames <= 0) {
            throw new IllegalArgumentException("warmupFrames must be positive, was " + warmupFrames);
        }
        if (maxMeasuredFps <= minMeasuredFps) {
            throw new IllegalArgumentException("maxMeasuredFps must exceed minMeasuredFps");
        }
        if (detectionBackoffInitialNanos <= 0) {
            throw new IllegalArgumentException("detectionBackoffInitialNanos must be positive");
        }
        if (detectionBackoffMaxNanos < detectionBackoffInitialNanos) {
            throw new IllegalArgumentException("detectionBackoffMaxNanos must be >= detectionBackoffInitialNanos");
        }
        if (sourceReopenBackoffInitialNanos <= 0) {
            throw new IllegalArgumentException("sourceReopenBackoffInitialNanos must be positive");
        }
        if (sourceReopenBackoffMaxNanos < sourceReopenBackoffInitialNanos) {
            throw new IllegalArgumentException(
                    "sourceReopenBackoffMaxNanos must be >= sourceReopenBackoffInitialNanos");
        }
        if (extrapolationMaxMillis < 0) {
            throw new IllegalArgumentException("extrapolationMaxMillis must not be negative");
        }
        if (extrapolationMatchGate < 0) {
            throw new IllegalArgumentException("extrapolationMatchGate must not be negative");
        }
        if (trackingStatsWindow == null || trackingStatsWindow.isZero() || trackingStatsWindow.isNegative()) {
            throw new IllegalArgumentException("trackingStatsWindow must be positive, was " + trackingStatsWindow);
        }
        if (trackRetention == null || trackRetention.isZero() || trackRetention.isNegative()) {
            throw new IllegalArgumentException("trackRetention must be positive, was " + trackRetention);
        }
        if (trackingSeed == null) {
            throw new IllegalArgumentException("trackingSeed must not be null; use TrackingConfigPatch.NOTHING");
        }
        if (!Double.isFinite(cameraHfovDegrees) || cameraHfovDegrees < 0.0 || cameraHfovDegrees >= 180.0) {
            throw new IllegalArgumentException(
                    "cameraHfovDegrees must be in [0, 180), was " + cameraHfovDegrees);
        }
        if (adaptiveRate == null) {
            throw new IllegalArgumentException(
                    "adaptiveRate must not be null; use AdaptiveRateSettings.disabled()");
        }
        if (detectionDemandPollInterval == null || detectionDemandPollInterval.isZero()
                || detectionDemandPollInterval.isNegative()) {
            throw new IllegalArgumentException(
                    "detectionDemandPollInterval must be positive, was " + detectionDemandPollInterval);
        }
        if (detectionDemandGrace == null || detectionDemandGrace.isZero() || detectionDemandGrace.isNegative()) {
            throw new IllegalArgumentException(
                    "detectionDemandGrace must be positive, was " + detectionDemandGrace);
        }
        if (videoStaleAfter == null || videoStaleAfter.isZero() || videoStaleAfter.isNegative()) {
            throw new IllegalArgumentException("videoStaleAfter must be positive, was " + videoStaleAfter);
        }
    }

    /** Every value byte-identical to the literal it replaces (docs/plans/active/LAYERING-REFACTOR-PLAN.md &sect;1.3). */
    public static StreamPipelineSettings defaults() {
        return new StreamPipelineSettings(
                30, 0.2, 5, 1.0, 240.0,
                1_000_000_000L, 10_000_000_000L,
                1_000_000_000L, 30_000_000_000L,
                800L, 0.15);
    }
}
