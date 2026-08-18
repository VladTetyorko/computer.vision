package com.drones.vision.flight.domain.model;

/**
 * The outcome of one Tier-A/B {@code PARAM_SET} attempt (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md section 6.2) -- snapshot before, read-back after: a write whose read-back
 * differs from what was requested is a failure, not a success, and {@code previousValue} is what the
 * caller restores to. {@code NO_ACK} means "nothing is known to have changed", not failure.
 *
 * @param parameterName the parameter written
 * @param outcome       how the aircraft responded
 * @param previousValue the value read back before the write; {@code null} if never read
 * @param newValue      the value read back after the write; {@code null} unless {@code outcome ==
 *                     ACCEPTED} and a read-back was actually performed
 * @param detail        an honest human sentence explaining the outcome; nullable
 */
public record ParameterWriteOutcome(String parameterName, RemediationResultCode outcome, Double previousValue,
                                     Double newValue, String detail) {

    public ParameterWriteOutcome {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("ParameterWriteOutcome parameterName must not be blank");
        }
        if (outcome == null) {
            throw new IllegalArgumentException("ParameterWriteOutcome outcome must not be null");
        }
    }
}
