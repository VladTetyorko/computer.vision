package com.drones.vision.perception.domain.model;

/**
 * What kind of evidence produced one frame's view of an object (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * §4.5).
 *
 * <p>A superset of {@link DetectionSource} in meaning, but a deliberately separate enum in fact:
 * {@code PREDICTED} is a pure coast with no observation at all, {@code MEMORY} is a re-acquisition
 * from the dormant gallery, {@code REUPDATE} is a box ORU reconstructed from the track's own
 * bracketing observations — none of which {@link DetectionSource}'s two values (detector pass vs.
 * cheap tracker pass) can express. Extending {@link DetectionSource} instead was rejected: the
 * codec maps it with an exhaustive Java switch expression with no {@code default} branch, so a new
 * value there breaks every Java client's build.
 *
 * <p>No {@code UNSPECIFIED} member, for the same reason {@link ObjectLifecycle} has none: the proto
 * zero value is a codec-boundary concern, not a fact this domain enum should be able to hold.
 */
public enum EvidenceSource {
    DETECTOR,
    TRACKER,
    PREDICTED,
    MEMORY,
    REUPDATE
}
