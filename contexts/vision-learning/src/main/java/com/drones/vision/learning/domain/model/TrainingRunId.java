package com.drones.vision.learning.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link TrainingRunRecord} (docs/plans/active/CV-SETTINGS-PLAN.md §3.3).
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so training
 * run identities cannot be mixed up with dataset, sample, or other typed identifiers at compile
 * time — following the same pattern as {@link DatasetId}/{@link TrainingSampleId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record TrainingRunId(UUID value) {

    public TrainingRunId {
        if (value == null) {
            throw new IllegalArgumentException("TrainingRunId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code TrainingRunId} using a random UUID.
     *
     * @return a fresh {@code TrainingRunId}
     */
    public static TrainingRunId random() {
        return new TrainingRunId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code TrainingRunId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code TrainingRunId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static TrainingRunId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("TrainingRunId value must not be null");
        }
        try {
            return new TrainingRunId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TrainingRunId value must be a valid UUID: " + value, e);
        }
    }
}
