package com.drones.vision.flight.domain.model;

/**
 * The remedy a {@link FeatureReadiness} row recommends. Frozen wire spelling
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §8.1) — the enum constant name is the wire string; a
 * row with no remedy is represented by a {@code null} {@link FeatureReadiness#remedy()}, not a
 * fifth constant here.
 */
public enum RemedyKind {
    /** Mechanism A — a runtime {@code SET_MESSAGE_INTERVAL} request, not a write at all. */
    MESSAGE_INTERVAL,
    /** Mechanism B — a Tier-A/B {@code PARAM_SET}, gated per {@link ParameterTier}. */
    PARAM_WRITE,
    /** Mechanism C — a generated CLI diff for a firmware this platform cannot write to directly. */
    CLI_SCRIPT,
    /** No automatable remedy exists; the operator must act outside this platform. */
    MANUAL
}
