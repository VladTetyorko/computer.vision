package com.drones.vision.domain.model;

import java.time.Instant;

/**
 * A detection tracked across frames under a stable track id.
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
