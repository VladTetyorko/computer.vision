package com.drones.vision.flight.domain.model;

/**
 * One parameter actually read off the aircraft during a {@link VehicleProfile} probe
 * (docs/plans/active/DRONE-ONBOARDING-PLAN.md §3.1 PROBE) — "only what was actually read" (§5.3):
 * a parameter this platform asked for and never got an answer to has no entry at all, rather than a
 * fabricated zero. {@code type} is the MAVLink parameter type tag as the vehicle reported it (e.g.
 * {@code "REAL32"}); kept as a plain string, mirroring {@code FlightState.firmware}'s own
 * plain-string convention for a vendor-reported vocabulary this module does not own.
 *
 * @param name  the parameter name as the vehicle reports it (e.g. {@code "SR2_EXTRA2"}); not blank
 * @param value the value, always carried as a {@code double} — the MAVLink parameter protocol wires
 *              every value as a float regardless of the type tag, so this is not a lossy
 *              simplification of what was actually received
 * @param type  the vehicle-reported type tag; not blank
 */
public record ParameterReading(String name, double value, String type) {

    public ParameterReading {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ParameterReading name must not be blank");
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("ParameterReading value must be finite: " + value);
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("ParameterReading type must not be blank");
        }
    }
}
