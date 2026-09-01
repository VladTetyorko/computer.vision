package com.drones.vision.perception.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link CvProfile}.
 *
 * @param value the underlying UUID; must not be null
 */
public record CvProfileId(UUID value) {

    public CvProfileId {
        if (value == null) {
            throw new IllegalArgumentException("CvProfileId value must not be null");
        }
    }

    /**
     * Generates a fresh random identity.
     *
     * @return a new {@code CvProfileId} backed by a random UUID
     */
    public static CvProfileId random() {
        return new CvProfileId(UUID.randomUUID());
    }

    /**
     * Parses a canonical UUID string.
     *
     * @param value the UUID string
     * @return the parsed identity
     * @throws IllegalArgumentException if {@code value} is null or not a valid UUID
     */
    public static CvProfileId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("CvProfileId value must not be null");
        }
        try {
            return new CvProfileId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("CvProfileId value must be a valid UUID: " + value, e);
        }
    }
}
