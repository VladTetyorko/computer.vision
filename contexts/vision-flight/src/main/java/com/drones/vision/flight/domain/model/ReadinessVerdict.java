package com.drones.vision.flight.domain.model;

/**
 * The overall verdict a {@link ReadinessReport} carries. Frozen wire spelling
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) — the enum constant name is the wire string.
 * {@link #UNKNOWN} must never be rendered as {@link #GO} by any caller — see {@link
 * com.drones.vision.flight.application.ReadinessService}'s own javadoc for exactly when it applies.
 */
public enum ReadinessVerdict {
    GO,
    NO_GO,
    UNKNOWN
}
