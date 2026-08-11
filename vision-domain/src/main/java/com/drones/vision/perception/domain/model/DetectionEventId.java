package com.drones.vision.perception.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link DetectionEvent}.
 *
 * @param value the underlying UUID; must not be null
 */
public record DetectionEventId(UUID value) {

    public DetectionEventId {
        if (value == null) {
            throw new IllegalArgumentException("DetectionEventId value must not be null");
        }
    }

    /**
     * Generates a fresh random identity.
     *
     * @return a new {@code DetectionEventId} backed by a random UUID
     */
    public static DetectionEventId random() {
        return new DetectionEventId(UUID.randomUUID());
    }

    /**
     * Parses a canonical UUID string.
     *
     * @param value the UUID string
     * @return the parsed identity
     * @throws IllegalArgumentException if {@code value} is null or not a valid UUID
     */
    public static DetectionEventId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DetectionEventId value must not be null");
        }
        try {
            return new DetectionEventId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DetectionEventId value must be a valid UUID: " + value, e);
        }
    }
}
