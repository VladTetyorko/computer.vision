package com.drones.vision.domain.model;

/**
 * Per-stream tracking configuration (docs/TRACKING-PLAN.md §4.A/§4.B), restated on every wire
 * frame request — deliberately declarative, never an imperative one-shot control message, so a
 * dropped frame or a reconnect can never desynchronize the tracker (docs/TRACKING-PLAN.md
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

    /** Default {@code FOLLOW} detector re-verify cadence (docs/TRACKING-PLAN.md §4.A), milliseconds. */
    public static final int DEFAULT_VERIFY_EVERY_MILLIS = 2000;

    /** Default Java-side sampler rate while {@code FOLLOW} is active (docs/TRACKING-ORCHESTRATION.md §4.3, D12). */
    public static final int DEFAULT_FOLLOW_FPS = 15;

    /** Default re-anchor IoU threshold, percent (docs/TRACKING-PLAN.md §4.A: 0.3 &rarr; 30). */
    public static final int DEFAULT_REDETECT_IOU_PERCENT = 30;

    /** Default unmatched-frames-before-{@code LOST} budget (docs/TRACKING-PLAN.md §4.A). */
    public static final int DEFAULT_MAX_AGE_FRAMES = 30;

    /** Default detector-hits-to-{@code CONFIRMED} budget (docs/TRACKING-PLAN.md §4.A). */
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
     * Tracking off — today's behavior, byte-identical. {@link PipelineConfig#defaults()} returns
     * this (deliberately <strong>not</strong> {@link #defaults()}) through docs/TRACKING-PLAN.md
     * waves T2–T7; the flip to {@link #defaults()} is wave T8's own single, reviewable commit
     * (docs/TRACKING-PLAN.md §5.G, docs/TRACKING-ORCHESTRATION.md D15).
     *
     * @return an {@code OFF} tracking configuration with no lock
     */
    public static TrackingConfig off() {
        return new TrackingConfig(TrackingMode.OFF, "", DEFAULT_VERIFY_EVERY_MILLIS, DEFAULT_FOLLOW_FPS,
                DEFAULT_REDETECT_IOU_PERCENT, DEFAULT_MAX_AGE_FRAMES, DEFAULT_MIN_HITS, null);
    }

    /**
     * The Mode-A default: every detection tracked across frames with a stable id, server-default
     * engine, and every cadence at its documented default. Not used by {@link
     * PipelineConfig#defaults()} until wave T8 — see {@link #off()}.
     *
     * @return an {@code ASSOCIATE} tracking configuration with no lock
     */
    public static TrackingConfig defaults() {
        return new TrackingConfig(TrackingMode.ASSOCIATE, "", DEFAULT_VERIFY_EVERY_MILLIS, DEFAULT_FOLLOW_FPS,
                DEFAULT_REDETECT_IOU_PERCENT, DEFAULT_MAX_AGE_FRAMES, DEFAULT_MIN_HITS, null);
    }
}
