package com.drones.vision.perception.domain.model;

/**
 * Lifecycle state of a {@link DetectionEvent} (docs/plans/done/MVP2-PLAN.md §E, E-a).
 *
 * <p>Pure marker set, no behavior — mirrors {@link Capability}/{@link EventType}/{@link
 * PixelFormat}'s convention.
 */
public enum DetectionEventState {
    /** The tracked label is currently qualifying (or was recently enough not to have timed out). */
    OPEN,
    /** The tracked label has been absent, or below threshold, for at least the configured absence window. */
    CLOSED
}
