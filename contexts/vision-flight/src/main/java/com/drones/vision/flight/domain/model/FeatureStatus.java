package com.drones.vision.flight.domain.model;

/**
 * One {@link FeatureReadiness} row's status. Frozen wire spelling
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) — the enum constant name is the wire string.
 */
public enum FeatureStatus {
    /** The evidence required for this feature is present and within threshold. */
    READY,
    /** Some evidence is present but below threshold (e.g. a message arriving too slowly). */
    DEGRADED,
    /** The required evidence is simply absent. */
    MISSING,
    /**
     * No requirement row exists for this firmware/feature pair, or the profile itself is
     * incomplete/never-probed — "a correct answer, not a failure" (§3.1).
     */
    UNKNOWN
}
