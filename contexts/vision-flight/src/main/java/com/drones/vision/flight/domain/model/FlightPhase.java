package com.drones.vision.flight.domain.model;

/**
 * Where one {@code AssetUsage} sits in the lifecycle a real flight actually has (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §2.3). {@code IDLE} — "no usage exists yet" — is deliberately not a value
 * here: it is the state before any {@code AssetUsage} record exists, so there is nothing to stamp a
 * phase onto. The frozen wire spelling ({@code docs/plans/active/DRONE-ONBOARDING-PLAN.md} §8.1) is
 * these six names exactly — an enum constant's own name doubles as the wire string, so no separate
 * mapping table exists or should be added.
 *
 * <p>Two rules the state machine depends on, both enforced by {@link FlightPhaseRule} rather than by
 * this enum itself (an enum cannot see the evidence a transition depends on):
 * <ul>
 *   <li>{@code armed == null} is never treated as {@code false} — a session with unknown arming
 *       stays in {@link #PREFLIGHT} and is never promoted or closed as "flew" (C7).</li>
 *   <li>{@link #ABANDONED} means the platform stopped observing while the aircraft was airborne — a
 *       real, recorded outcome, never confused with a normal {@link #POSTFLIGHT} landing.</li>
 * </ul>
 */
public enum FlightPhase {
    /** On the ground, powered, disarmed (or arming still unknown). */
    PREFLIGHT,
    /** Armed and, so far as the platform can tell, flying. */
    IN_FLIGHT,
    /** No telemetry for at least the configured silence window; last known state is presumed stale. */
    LINK_LOST,
    /** Landed and disarmed, still powered; config-drift is computed here. */
    POSTFLIGHT,
    /** The platform stopped observing while the aircraft was airborne — a real outcome, not an error. */
    ABANDONED,
    /** Terminal. The record is now immutable. */
    CLOSED
}
