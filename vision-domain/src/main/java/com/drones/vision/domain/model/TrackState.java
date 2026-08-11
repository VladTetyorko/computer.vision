package com.drones.vision.domain.model;

/**
 * Lifecycle state of the track a {@link Detection} belongs to (docs/TRACKING-PLAN.md §3.2):
 * {@code TENTATIVE → CONFIRMED → COASTING → LOST → (expired, id retired)}.
 *
 * <p>{@code TENTATIVE} is born, below {@link TrackingConfig#minHits()} detector confirmations —
 * deliberately not rendered as a stable identity to the UI, the anti-flicker gate. {@code
 * CONFIRMED} is a real, detector-backed object. {@code COASTING} is tracker-predicted only, not
 * re-confirmed by the most recent detector pass. {@code LOST} is unmatched past {@link
 * TrackingConfig#maxAgeFrames()}, kept in the application layer's track book so a re-appearance
 * after a brief occlusion recovers the same id. Pure marker, no behavior, no dedicated test (same
 * convention as {@code Capability}/{@code EventType}/{@code PixelFormat}).
 */
public enum TrackState {
    TENTATIVE,
    CONFIRMED,
    COASTING,
    LOST
}
