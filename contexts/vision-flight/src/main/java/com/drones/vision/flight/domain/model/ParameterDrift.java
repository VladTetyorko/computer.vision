package com.drones.vision.flight.domain.model;

import java.time.Instant;

/**
 * One parameter whose value differed between two {@link VehicleProfile} snapshots of the same
 * aircraft, taken on two different flights (docs/plans/active/DRONE-ONBOARDING-PLAN.md O11 -- the
 * passport's config-drift half). The only intended producer is {@link ConfigDriftCalculator#diff};
 * this type's own invariant (below) exists so a caller cannot manufacture a "drift" that is not one.
 *
 * @param parameterName      the drifted parameter's name, exactly as reported by the aircraft
 * @param previousValue      the value read in the earlier snapshot
 * @param currentValue       the value read in the later snapshot; never equal to {@code
 *                            previousValue} -- a non-change is not a drift (C7: this type cannot
 *                            represent "probably the same")
 * @param previousObservedAt when the earlier snapshot was taken
 * @param currentObservedAt  when the later snapshot was taken; not before {@code previousObservedAt}
 */
public record ParameterDrift(
        String parameterName,
        double previousValue,
        double currentValue,
        Instant previousObservedAt,
        Instant currentObservedAt) {

    public ParameterDrift {
        if (parameterName == null || parameterName.isBlank()) {
            throw new IllegalArgumentException("ParameterDrift parameterName must not be blank");
        }
        if (previousObservedAt == null) {
            throw new IllegalArgumentException("ParameterDrift previousObservedAt must not be null");
        }
        if (currentObservedAt == null) {
            throw new IllegalArgumentException("ParameterDrift currentObservedAt must not be null");
        }
        if (currentObservedAt.isBefore(previousObservedAt)) {
            throw new IllegalArgumentException(
                    "ParameterDrift currentObservedAt must not be before previousObservedAt: "
                            + currentObservedAt + " < " + previousObservedAt);
        }
        if (Double.compare(previousValue, currentValue) == 0) {
            throw new IllegalArgumentException(
                    "ParameterDrift previousValue and currentValue must differ (a non-change is not "
                            + "a drift): both are " + previousValue);
        }
    }
}
