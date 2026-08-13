package com.drones.vision.platform;

import java.util.UUID;

/**
 * Typed identity for an {@link AuditEntry}.
 *
 * @param value the underlying UUID; must not be null
 */
public record AuditId(UUID value) {

    public AuditId {
        if (value == null) {
            throw new IllegalArgumentException("AuditId value must not be null");
        }
    }

    /**
     * Generates a fresh random identity.
     *
     * @return a new {@code AuditId} backed by a random UUID
     */
    public static AuditId random() {
        return new AuditId(UUID.randomUUID());
    }

    /**
     * Parses a canonical UUID string.
     *
     * @param value the UUID string
     * @return the parsed identity
     * @throws IllegalArgumentException if {@code value} is null or not a valid UUID
     */
    public static AuditId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("AuditId value must not be null");
        }
        try {
            return new AuditId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AuditId value must be a valid UUID: " + value, e);
        }
    }
}
