package com.drones.vision.perception.domain.model;

import java.time.Duration;

/**
 * Per-frame tracking facts (docs/plans/done/TRACKING-PLAN.md §4.B; docs/extracts/TRACKING-ORCHESTRATION.md §5.2) — as
 * opposed to {@link TrackRef}'s per-detection facts, this is what happened on the frame as a
 * whole: did a detector pass run, why, how long the tracker took, which engine actually served
 * it, and which track is currently locked.
 *
 * <p>This closes the gap TRACKING-ORCHESTRATION.md §5.2 names: {@link DetectionResult} had five
 * components and none of them could carry {@code detectorRan}, so a later wave's SSE
 * {@code "detectorRan"} field would have had no source to map from. One nullable component on
 * {@code DetectionResult}, not five flat ones — {@code null} there means tracking was off for
 * that result, exactly as {@link Detection#track()} being {@code null} means "untracked"
 * (docs/extracts/TRACKING-ORCHESTRATION.md §6 rules 1–2).
 *
 * <p>{@code detectionLag}, {@code reupdateLatency}, {@code reupdatedTracks} and {@code capability}
 * (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md §2) are the four V3 response facts this side never
 * read before this wave. Same "absent means absent" idiom this type already documents above for the
 * five pre-V3 fields: a response whose six V3 fields are all at their wire zero-value — the shape an
 * old, pre-V3 cv-service returns — must decode to {@code detectionLag}/{@code reupdateLatency} both
 * {@link Duration#ZERO}, {@code reupdatedTracks} {@code 0}, and {@code capability} {@code null}
 * (invariant B3), never a fabricated zero-level {@link TrackingCapability}.
 *
 * @param detectorRan      whether a full detector pass ran on this frame ({@code false} = this
 *                         frame was tracker-only, duty-cycled)
 * @param reason           why a detector pass ran; {@code null} when {@code detectorRan} is
 *                         {@code false} (mirrors the wire's {@code DETECTOR_REASON_UNSPECIFIED});
 *                         must not be {@code null} when {@code detectorRan} is {@code true}
 * @param trackerLatency   per-frame tracker cost; {@link Duration#ZERO} when no tracker ran; must
 *                         not be negative
 * @param engineId         the engine that actually served this frame; empty ({@code ""}) means none
 *                         — not necessarily the engine the stream's {@link TrackingConfig} requested
 *                         (a fallback may have degraded it)
 * @param lockedTrackId    the track currently held under {@code FOLLOW}; {@code 0} = none
 * @param detectionLag     measured capture&rarr;association lag; {@link Duration#ZERO} = unknown;
 *                         must not be negative
 * @param reupdateLatency  ORU cost this frame; {@link Duration#ZERO} = none ran; must not be negative
 * @param reupdatedTracks  tracks backfilled by ORU this frame; must not be negative
 * @param capability       what the capability ladder actually served this frame; {@code null} =
 *                         cv-service reported no level at all (a pre-V3 server)
 */
public record TrackingTelemetry(boolean detectorRan, DetectorReason reason, Duration trackerLatency,
                                 String engineId, long lockedTrackId, Duration detectionLag,
                                 Duration reupdateLatency, int reupdatedTracks, TrackingCapability capability) {

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
        if (detectionLag == null) {
            throw new IllegalArgumentException("TrackingTelemetry detectionLag must not be null");
        }
        if (detectionLag.isNegative()) {
            throw new IllegalArgumentException(
                    "TrackingTelemetry detectionLag must not be negative: " + detectionLag);
        }
        if (reupdateLatency == null) {
            throw new IllegalArgumentException("TrackingTelemetry reupdateLatency must not be null");
        }
        if (reupdateLatency.isNegative()) {
            throw new IllegalArgumentException(
                    "TrackingTelemetry reupdateLatency must not be negative: " + reupdateLatency);
        }
        if (reupdatedTracks < 0) {
            throw new IllegalArgumentException(
                    "TrackingTelemetry reupdatedTracks must not be negative: " + reupdatedTracks);
        }
    }

    /**
     * Convenience constructor for callers that don't care about the V3 fields — defaults {@link
     * #detectionLag()}/{@link #reupdateLatency()} to {@link Duration#ZERO}, {@link
     * #reupdatedTracks()} to {@code 0} and {@link #capability()} to {@code null}, the "absent means
     * absent" shape a pre-V3 cv-service response decodes to (invariant B3). This was the canonical
     * constructor before docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md added the four fields; every
     * pre-existing 5-arg call site compiles <em>and behaves</em> unchanged.
     */
    public TrackingTelemetry(boolean detectorRan, DetectorReason reason, Duration trackerLatency, String engineId,
                              long lockedTrackId) {
        this(detectorRan, reason, trackerLatency, engineId, lockedTrackId, Duration.ZERO, Duration.ZERO, 0, null);
    }
}
