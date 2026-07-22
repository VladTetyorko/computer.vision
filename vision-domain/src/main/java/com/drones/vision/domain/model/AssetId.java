package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for an {@link Asset}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so asset identities cannot be mixed up with device,
 * stream, or other typed identifiers at compile time, and can never hold a
 * malformed value — following the same pattern as {@link DeviceId} and
 * {@link StreamId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record AssetId(UUID value) {

    public AssetId {
        if (value == null) {
            throw new IllegalArgumentException("AssetId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code AssetId} using a random UUID.
     *
     * @return a fresh {@code AssetId}
     */
    public static AssetId random() {
        return new AssetId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into an {@code AssetId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code AssetId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static AssetId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("AssetId value must not be null");
        }
        try {
            return new AssetId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("AssetId value must be a valid UUID: " + value, e);
        }
    }
}
