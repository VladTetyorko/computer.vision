package com.drones.vision.perception.domain.model;

/**
 * Per-stream tracking configuration (docs/plans/done/TRACKING-PLAN.md §4.A/§4.B), restated on every wire
 * frame request — deliberately declarative, never an imperative one-shot control message, so a
 * dropped frame or a reconnect can never desynchronize the tracker (docs/plans/done/TRACKING-PLAN.md
 * invariant P2).
 *
 * <p>Joins {@link PipelineConfig} as its {@code tracking} component. {@code engineId} empty means
 * "server default for the mode". {@code redetectIouPercent} is an {@code int} percent [0,100],
 * not a {@code double}, so this record stays trivially JSON-/PATCH-friendly and there is one
 * fewer float-comparison test; wire-side it converts to the proto {@code float}
 * {@code redetect_iou_threshold}. {@code lock} is {@code null} whenever no lock request/
 * confirmation is in flight for the stream — not a {@link TargetLock} with every form absent.
 *
 * @param mode               how the two perception loops cooperate for this stream
 * @param engineId           tracker engine to use; empty ({@code ""}) means the server picks the
 *                           mode's default
 * @param verifyEveryMillis  {@code FOLLOW} detector re-verify cadence, milliseconds; must be positive
 * @param followFps          the Java-side sampler's target frame rate while {@code FOLLOW} is
 *                           active — no cv-service knob, since cv-service must never care how
 *                           often it is fed; must be positive
 * @param redetectIouPercent re-anchor when detector&harr;tracker IoU is at least this percent, [0,100]
 * @param maxAgeFrames       unmatched frames before a track goes {@code LOST}; must be positive
 * @param minHits            detector hits needed for {@code TENTATIVE} &rarr; {@code CONFIRMED};
 *                           must be positive
 * @param lock                the target {@code FOLLOW} should hold, or {@code null} if none
 */
public record TrackingConfig(TrackingMode mode, String engineId, int verifyEveryMillis, int followFps,
                              int redetectIouPercent, int maxAgeFrames, int minHits, TargetLock lock) {

    /** Default {@code FOLLOW} detector re-verify cadence (docs/plans/done/TRACKING-PLAN.md §4.A), milliseconds. */
    public static final int DEFAULT_VERIFY_EVERY_MILLIS = 2000;

    /** Default Java-side sampler rate while {@code FOLLOW} is active (docs/extracts/TRACKING-ORCHESTRATION.md §4.3, D12). */
    public static final int DEFAULT_FOLLOW_FPS = 15;

    /** Default re-anchor IoU threshold, percent (docs/plans/done/TRACKING-PLAN.md §4.A: 0.3 &rarr; 30). */
    public static final int DEFAULT_REDETECT_IOU_PERCENT = 30;

    /** Default unmatched-frames-before-{@code LOST} budget (docs/plans/done/TRACKING-PLAN.md §4.A). */
    public static final int DEFAULT_MAX_AGE_FRAMES = 30;

    /** Default detector-hits-to-{@code CONFIRMED} budget (docs/plans/done/TRACKING-PLAN.md §4.A). */
    public static final int DEFAULT_MIN_HITS = 3;

    public TrackingConfig {
        if (mode == null) {
            throw new IllegalArgumentException("TrackingConfig mode must not be null");
        }
        if (engineId == null) {
            throw new IllegalArgumentException("TrackingConfig engineId must not be null");
        }
        if (verifyEveryMillis <= 0) {
            throw new IllegalArgumentException(
                    "TrackingConfig verifyEveryMillis must be positive: " + verifyEveryMillis);
        }
        if (followFps <= 0) {
            throw new IllegalArgumentException("TrackingConfig followFps must be positive: " + followFps);
        }
        if (redetectIouPercent < 0 || redetectIouPercent > 100) {
            throw new IllegalArgumentException(
                    "TrackingConfig redetectIouPercent must be within [0,100]: " + redetectIouPercent);
        }
        if (maxAgeFrames <= 0) {
            throw new IllegalArgumentException("TrackingConfig maxAgeFrames must be positive: " + maxAgeFrames);
        }
        if (minHits <= 0) {
            throw new IllegalArgumentException("TrackingConfig minHits must be positive: " + minHits);
        }
    }

    /**
     * Tracking off — the detector runs on every sampled frame and no detection carries an id, which
     * is byte-identical to how the pipeline behaved before tracking existed. Reachable two ways:
     * {@code PATCH /api/streams/{id}/config} for one stream, {@code vision.tracking.default-mode=OFF}
     * for a deployment. It is <strong>no longer</strong> what {@link PipelineConfig#defaults()}
     * returns — that flipped to {@link #defaults()} in docs/plans/done/TRACKING-PLAN.md wave T8 (§5.G).
     *
     * <p>Still the value every {@link PipelineConfig} <em>convenience</em> constructor defaults to,
     * so a call site written before tracking existed behaves exactly as it did.
     *
     * @return an {@code OFF} tracking configuration with no lock
     */
    public static TrackingConfig off() {
        return new TrackingConfig(TrackingMode.OFF, "", DEFAULT_VERIFY_EVERY_MILLIS, DEFAULT_FOLLOW_FPS,
                DEFAULT_REDETECT_IOU_PERCENT, DEFAULT_MAX_AGE_FRAMES, DEFAULT_MIN_HITS, null);
    }

    /**
     * The Mode-A default: every detection tracked across frames with a stable id, server-default
     * engine, and every cadence at its documented default. <strong>This is what {@link
     * PipelineConfig#defaults()} ships</strong> as of docs/plans/done/TRACKING-PLAN.md wave T8 (§5.G) — a new
     * stream associates unless something says otherwise.
     *
     * <p>{@code ASSOCIATE} costs one association pass per <em>sampled</em> frame (measured ~0.78 ms
     * against a 22.8 ms {@code yolo26n} detector pass, cv-service/MODULE.md), and never raises the
     * sample rate — {@code FOLLOW} is the mode that does.
     *
     * @return an {@code ASSOCIATE} tracking configuration with no lock
     */
    public static TrackingConfig defaults() {
        return new TrackingConfig(TrackingMode.ASSOCIATE, "", DEFAULT_VERIFY_EVERY_MILLIS, DEFAULT_FOLLOW_FPS,
                DEFAULT_REDETECT_IOU_PERCENT, DEFAULT_MAX_AGE_FRAMES, DEFAULT_MIN_HITS, null);
    }
}
