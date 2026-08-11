package com.drones.vision.learning.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link Dataset} (docs/plans/done/CV-TRAINING-PLAN.md §1).
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so dataset
 * identities cannot be mixed up with asset, stream, or other typed identifiers at compile time —
 * following the same pattern as {@link AssetId}/{@link StreamId}/{@link ZoneId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record DatasetId(UUID value) {

    public DatasetId {
        if (value == null) {
            throw new IllegalArgumentException("DatasetId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code DatasetId} using a random UUID.
     *
     * @return a fresh {@code DatasetId}
     */
    public static DatasetId random() {
        return new DatasetId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code DatasetId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code DatasetId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static DatasetId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DatasetId value must not be null");
        }
        try {
            return new DatasetId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DatasetId value must be a valid UUID: " + value, e);
        }
    }
}
