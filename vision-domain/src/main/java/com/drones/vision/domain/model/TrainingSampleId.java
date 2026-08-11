package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link TrainingSample} (docs/plans/done/CV-TRAINING-PLAN.md §1).
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so training
 * sample identities cannot be mixed up with asset, stream, or other typed identifiers at compile
 * time — following the same pattern as {@link AssetId}/{@link StreamId}/{@link DatasetId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record TrainingSampleId(UUID value) {

    public TrainingSampleId {
        if (value == null) {
            throw new IllegalArgumentException("TrainingSampleId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code TrainingSampleId} using a random UUID.
     *
     * @return a fresh {@code TrainingSampleId}
     */
    public static TrainingSampleId random() {
        return new TrainingSampleId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code TrainingSampleId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code TrainingSampleId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static TrainingSampleId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TrainingSampleId value must not be null");
        }
        try {
            return new TrainingSampleId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TrainingSampleId value must be a valid UUID: " + value, e);
        }
    }
}
