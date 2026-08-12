package com.drones.vision.map.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link Mark}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so mark
 * identities cannot be mixed up with asset, zone, or other typed identifiers at compile time —
 * following the same pattern as {@link ZoneId}/{@link AssetId}/{@link DeviceId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record MarkId(UUID value) {

    public MarkId {
        if (value == null) {
            throw new IllegalArgumentException("MarkId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code MarkId} using a random UUID.
     *
     * @return a fresh {@code MarkId}
     */
    public static MarkId random() {
        return new MarkId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code MarkId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code MarkId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static MarkId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("MarkId value must not be null");
        }
        try {
            return new MarkId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MarkId value must be a valid UUID: " + value, e);
        }
    }
}
