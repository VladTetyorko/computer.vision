package com.drones.vision.domain.model;

/**
 * Why a detector pass was spent on a frame (docs/TRACKING-PLAN.md §3.1, §4.A;
 * docs/TRACKING-ORCHESTRATION.md §5.1) — {@link TrackingTelemetry#detectorRan()} says
 * <em>whether</em> a pass ran; this says <em>why</em>, one value per trigger in the duty-cycle
 * table.
 *
 * <p>Mirrors {@code cv.proto}'s {@code DetectorReason} minus {@code
 * DETECTOR_REASON_UNSPECIFIED} — mapping that proto zero-value ("old server, or no detector pass
 * ran on this frame") is an adapter-boundary concern, not this enum's, the same posture {@link
 * JobState} already takes toward {@code JOB_STATE_UNSPECIFIED}. See {@link
 * TrackingTelemetry#reason()} for the corresponding domain nullability — {@code null} there is
 * this enum's "no pass ran, no reason to give" case.
 */
public enum DetectorReason {
    /** Mode {@code OFF}/{@code ASSOCIATE} — every received frame gets a pass. */
    ALWAYS,
    /** {@code verifyEveryMillis} elapsed since the last pass. */
    CADENCE,
    /** The tracker reported failure or sub-threshold confidence. */
    TRACKER_FAILED,
    /** No lock is currently held — re-acquire. */
    NO_LOCK,
    /** The predicted box left the frame or collapsed. */
    BOX_INVALID,
    /** The track has been {@code COASTING} longer than {@code maxAgeFrames}. */
    COASTED_OUT
}
