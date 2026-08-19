package com.drones.vision.flight.domain.model;

/**
 * The outcome of one remediation action attempted against a vehicle (Mechanism A message-interval
 * request or a Tier-A/B {@code PARAM_SET}). Frozen wire spelling (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md, section 8.1, {@code RemediationResult.actions[].outcome}) — not named as
 * its own type in the plan's module-placement table; added here to give {@link
 * com.drones.vision.flight.domain.port.VehicleConfigPort}'s write/message-interval methods a return
 * type that can honestly distinguish these four cases rather than collapsing them to a boolean.
 * Flagged in this wave's report as an extension beyond the plan's explicit type list, filling a
 * genuine gap.
 */
public enum RemediationResultCode {
    /** The aircraft's own acknowledgement confirmed the change. */
    ACCEPTED,
    /** The aircraft explicitly refused. */
    DENIED,
    /** No acknowledgement arrived within the timeout -- neither success nor failure. */
    NO_ACK,
    /** The vehicle/firmware does not support this action at all. */
    UNSUPPORTED
}
