package com.drones.vision.warehouse.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Typed identity for a {@link Pairing} — exact template {@code com.drones.vision.kernel.DeviceId}
 * already applies to every id in the kernel.
 *
 * @param value the wrapped UUID, never {@code null}
 */
public record PairingId(UUID value) {

    public PairingId {
        Objects.requireNonNull(value, "PairingId value must not be null");
    }

    /**
     * Mints a fresh id.
     *
     * @return a new random id
     */
    public static PairingId random() {
        return new PairingId(UUID.randomUUID());
    }

    /**
     * Parses a canonical UUID string.
     *
     * @param value the string form
     * @return the parsed id
     * @throws IllegalArgumentException if {@code value} is not a valid UUID
     */
    public static PairingId of(String value) {
        try {
            return new PairingId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid PairingId: " + value, e);
        }
    }
}
