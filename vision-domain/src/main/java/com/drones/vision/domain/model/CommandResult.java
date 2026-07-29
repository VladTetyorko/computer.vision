package com.drones.vision.domain.model;

/**
 * Outcome of a flight command sent through {@link
 * com.drones.vision.domain.port.out.FlightCommandPort} (docs/DRONE-INFRA-PLAN.md I-e, Stage 1).
 *
 * <p>An explicit refusal by the aircraft is deliberately <b>not</b> a value of this enum — see
 * {@link com.drones.vision.domain.port.out.FlightCommandPort}'s own javadoc for why that is
 * instead a thrown {@link IllegalStateException}. This type only distinguishes the two outcomes
 * that are not exceptional: the command was accepted, or nothing came back to say either way.
 */
public enum CommandResult {

    /** The aircraft's own acknowledgement reported the command as accepted. */
    ACCEPTED,

    /**
     * No acknowledgement arrived within the port's timeout. UDP is lossy in both directions — the
     * command, or its acknowledgement, may simply have been dropped; the aircraft may have applied
     * it anyway. This value is honest about that uncertainty rather than guessing either way.
     */
    NO_ACK
}
