package com.drones.vision.warehouse.domain.model;

/**
 * Where a {@link DiscoveryCandidate} stands with an operator (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a).
 *
 * <p>Deliberately no {@code EXPIRED} value: a candidate's staleness is never stored, only derived
 * by a reader from {@link DiscoveryCandidate#lastSeen()} and shown as an age — the honesty
 * convention this codebase already applies to "reconnecting"/"stale" telemetry facts (CLAUDE.md
 * &sect;9). A {@link #NEW} candidate heard three days ago is still {@code NEW}, just old; a reader
 * decides whether that is worth surfacing, not this enum.
 */
public enum CandidateStatus {

    /** Reported and not yet acted on by an operator. */
    NEW,

    /**
     * An operator dismissed it. A re-report never revives a dismissed candidate back to {@link
     * #NEW} on its own — the operator's gesture outlives the announcer — but a re-report that now
     * matches an already-registered device still moves it to {@link #REGISTERED}: that is an
     * objective fact about the fleet, not a "should I show this" recommendation.
     */
    DISMISSED,

    /**
     * Registered as an asset — either through this candidate's own {@link
     * com.drones.vision.warehouse.application.discovery.DiscoveryInboxService#register}, or
     * discovered on a later report to already match a registered device by identity.
     */
    REGISTERED
}
