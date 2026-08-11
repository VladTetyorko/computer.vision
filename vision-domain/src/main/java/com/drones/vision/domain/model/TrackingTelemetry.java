package com.drones.vision.domain.model;

import java.time.Duration;

/**
 * Per-frame tracking facts (docs/TRACKING-PLAN.md §4.B; docs/TRACKING-ORCHESTRATION.md §5.2) — as
 * opposed to {@link TrackRef}'s per-detection facts, this is what happened on the frame as a
 * whole: did a detector pass run, why, how long the tracker took, which engine actually served
 * it, and which track is currently locked.
 *
 * <p>This closes the gap TRACKING-ORCHESTRATION.md §5.2 names: {@link DetectionResult} had five
 * components and none of them could carry {@code detectorRan}, so a later wave's SSE
 * {@code "detectorRan"} field would have had no source to map from. One nullable component on
 * {@code DetectionResult}, not five flat ones — {@code null} there means tracking was off for
 * that result, exactly as {@link Detection#track()} being {@code null} means "untracked"
 * (docs/TRACKING-ORCHESTRATION.md §6 rules 1–2).
 *
 * @param detectorRan    whether a full detector pass ran on this frame ({@code false} = this
 *                       frame was tracker-only, duty-cycled)
 * @param reason         why a detector pass ran; {@code null} when {@code detectorRan} is
 *                       {@code false} (mirrors the wire's {@code DETECTOR_REASON_UNSPECIFIED});
 *                       must not be {@code null} when {@code detectorRan} is {@code true}
 * @param trackerLatency per-frame tracker cost; {@link Duration#ZERO} when no tracker ran; must
 *                       not be negative
 * @param engineId       the engine that actually served this frame; empty ({@code ""}) means none
 *                       — not necessarily the engine the stream's {@link TrackingConfig} requested
 *                       (a fallback may have degraded it)
 * @param lockedTrackId  the track currently held under {@code FOLLOW}; {@code 0} = none
 */
public record TrackingTelemetry(boolean detectorRan, DetectorReason reason, Duration trackerLatency,
                                 String engineId, long lockedTrackId) {

    public TrackingTelemetry {
        if (detectorRan && reason == null) {
            throw new IllegalArgumentException("TrackingTelemetry reason must not be null when detectorRan is true");
        }
        if (trackerLatency == null) {
            throw new IllegalArgumentException("TrackingTelemetry trackerLatency must not be null");
        }
        if (trackerLatency.isNegative()) {
            throw new IllegalArgumentException(
                    "TrackingTelemetry trackerLatency must not be negative: " + trackerLatency);
        }
        if (engineId == null) {
            throw new IllegalArgumentException("TrackingTelemetry engineId must not be null");
        }
        if (lockedTrackId < 0) {
            throw new IllegalArgumentException(
                    "TrackingTelemetry lockedTrackId must not be negative: " + lockedTrackId);
        }
    }
}
