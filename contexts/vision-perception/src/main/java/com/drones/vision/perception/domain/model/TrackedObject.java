package com.drones.vision.perception.domain.model;

import java.time.Instant;

/**
 * A detection tracked across frames under a stable track id — the application layer's track-book
 * entry (docs/plans/done/TRACKING-PLAN.md §4.B): {@code trackId} is the book's key, {@code detection} its
 * latest observation (whose {@link Detection#track()} carries the lifecycle state, source, and
 * velocity), {@code firstSeen}/{@code lastSeen} the track's lifetime. Populated and maintained by
 * {@code TrackBook} (vision-application), which books what arrived and associates nothing itself
 * — the association happens upstream, in cv-service.
 *
 * <p>Deliberately <strong>not</strong> validated against {@code detection.track().trackId()}
 * matching this record's own {@code trackId} — a compact-constructor invariant tying the two
 * together would break this record's ability to hold an untracked detection (tracking off, or a
 * detection that predates a track association) and its existing tests.
 *
 * @param trackId   stable identity of the tracked object within its stream; must be non-negative
 * @param detection most recent detection for this track
 * @param firstSeen timestamp this track was first observed
 * @param lastSeen  timestamp this track was last observed; must not be before {@code firstSeen}
 */
public record TrackedObject(long trackId, Detection detection, Instant firstSeen, Instant lastSeen) {

    public TrackedObject {
        if (trackId < 0) {
            throw new IllegalArgumentException("TrackedObject trackId must not be negative: " + trackId);
        }
        if (detection == null) {
            throw new IllegalArgumentException("TrackedObject detection must not be null");
        }
        if (firstSeen == null) {
            throw new IllegalArgumentException("TrackedObject firstSeen must not be null");
        }
        if (lastSeen == null) {
            throw new IllegalArgumentException("TrackedObject lastSeen must not be null");
        }
        if (lastSeen.isBefore(firstSeen)) {
            throw new IllegalArgumentException("TrackedObject lastSeen must not be before firstSeen");
        }
    }
}
