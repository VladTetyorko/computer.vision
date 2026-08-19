package com.drones.mavlink.service;

import io.dronefleet.mavlink.common.MavParamType;

import java.util.Objects;

/**
 * One aircraft parameter, exactly as the vehicle reported it in a {@code PARAM_VALUE}.
 *
 * <p>Every parameter travels the wire as a {@code float}, whatever the vehicle stores it as —
 * {@link #type} is the vehicle's own claim about the underlying storage, kept so a caller can render
 * {@code 5.0} as {@code 5} for an {@code INT8} without guessing. This record deliberately does not
 * pre-convert: rounding an {@code INT32} parameter through a {@code float} is lossy above 2^24, and
 * silently doing it here would hide that from the one caller who cares.
 *
 * @param name  the vehicle's {@code param_id}, already normalised (NUL padding stripped, ≤16 chars)
 * @param index the parameter's index in the vehicle's own table — informational only; never used to
 *              correlate, because indexes shift between firmware builds
 * @param count how many parameters the vehicle has in total, as it reported in this message
 */
public record ParameterValue(String name, float value, MavParamType type, int index, int count) {

    public ParameterValue {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** {@code true} if this is the parameter {@code expected} names, comparing on the normalised form. */
    public boolean isNamed(String expected) {
        return name.equals(com.drones.mavlink.session.CorrelationKeys.normalizeParamId(Objects.requireNonNull(expected, "expected")));
    }
}
