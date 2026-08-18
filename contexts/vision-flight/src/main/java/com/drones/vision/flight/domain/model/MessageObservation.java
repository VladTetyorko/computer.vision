package com.drones.vision.flight.domain.model;

/**
 * One entry of a {@link VehicleProfile}'s passive message inventory — "this message id arrived at
 * roughly this rate, this many times, during the probe window" (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md §3.1 PROBE, O1's inventory). {@code name} is resolved by whichever
 * adapter built the observation (it owns the message dictionary); {@code null} for a message id this
 * platform does not have a name for — an unresolved name is still counted, never dropped.
 *
 * @param messageId the wire message id (e.g. 33 for {@code GLOBAL_POSITION_INT})
 * @param name      the human-readable message name, or {@code null} if unresolved
 * @param hz        the observed rate over the probe window; non-negative
 * @param count     how many times the message was seen; non-negative
 */
public record MessageObservation(int messageId, String name, double hz, long count) {

    public MessageObservation {
        if (messageId < 0) {
            throw new IllegalArgumentException("messageId must not be negative: " + messageId);
        }
        if (Double.isNaN(hz) || Double.isInfinite(hz) || hz < 0) {
            throw new IllegalArgumentException("hz must be finite and non-negative: " + hz);
        }
        if (count < 0) {
            throw new IllegalArgumentException("count must not be negative: " + count);
        }
    }
}
