package com.drones.vision.perception.domain.model;

/**
 * Where one identity is in its life (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.5).
 *
 * <p>{@code LOST} is the last state today's {@link TrackState}/wire can report; {@code DORMANT} is
 * the one this adds — a retired identity still held in cv-service's per-stream gallery and still
 * recoverable under its own number, a state today's wire could never report at all. Not a value of
 * {@link TrackState}, and deliberately its own enum rather than an extension of it: the codec maps
 * {@code TrackState} with an exhaustive Java switch expression with no {@code default} branch, so
 * adding a value there breaks the build of every Java client instead of merely this one.
 *
 * <p>No {@code UNSPECIFIED} member: the proto zero value is a wire concern the codec maps (an
 * unspecified/unrecognized object is dropped at the codec, the same discipline {@link TrackRef}
 * already applies), and a domain enum must not carry a "we don't know" member.
 */
public enum ObjectLifecycle {
    /** Born, below {@code min_hits} — not yet a stable identity. */
    TENTATIVE,
    /** Confirmed by the detector on this or a recent pass. */
    CONFIRMED,
    /** Tracker-predicted; the detector has not re-confirmed yet. */
    COASTING,
    /** Unmatched past {@code max_age_frames}; kept for re-acquisition. */
    LOST,
    /** Retired into the gallery; matchable, not currently tracked. */
    DORMANT
}
