package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link GeofenceZone}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so zone
 * identities cannot be mixed up with asset, device, or other typed identifiers at compile time —
 * following the same pattern as {@link AssetId}/{@link DeviceId}/{@link StreamId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record ZoneId(UUID value) {

    public ZoneId {
        if (value == null) {
            throw new IllegalArgumentException("ZoneId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code ZoneId} using a random UUID.
     *
     * @return a fresh {@code ZoneId}
     */
    public static ZoneId random() {
        return new ZoneId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code ZoneId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code ZoneId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static ZoneId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ZoneId value must not be null");
        }
        try {
            return new ZoneId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ZoneId value must be a valid UUID: " + value, e);
        }
    }
}
