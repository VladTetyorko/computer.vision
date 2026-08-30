package com.drones.vision.learning.domain.model;

import java.time.Instant;

/**
 * Where a {@link CvModelRecord} came from (docs/plans/active/CV-SETTINGS-PLAN.md §3.2) — links a
 * catalogue row back to the {@link TrainingRunRecord} and dataset that produced it, when one did.
 *
 * <p>A config-seeded model (a bundled checkpoint the deployment ships with, never trained through
 * this platform's loop) has no provenance to report at all; that case is represented by {@link
 * #none()}, an explicit sentinel with every field {@code null}, rather than letting {@link
 * CvModelRecord#provenance()} itself be {@code null} — every caller can dereference {@code
 * provenance().datasetId()} etc. without a null check on the provenance value itself first.
 *
 * <p>Every field is independently nullable by design, not all-or-nothing: a hand-registered model
 * might name a {@code baseModel} and {@code epochs} without a tracked {@link TrainingRunId} (e.g.
 * trained outside this platform), or a run might exist without every field populated.
 *
 * @param datasetId     the dataset trained on, or {@code null} if unknown/not applicable
 * @param trainingRunId the {@link TrainingRunRecord} that produced this model, or {@code null}
 * @param baseModel     the checkpoint the run started from, or {@code null}; must not be blank when
 *                      present
 * @param epochs        epochs trained for, or {@code null}; must be positive when present
 * @param trainedAt     when training finished, or {@code null}
 */
public record ModelProvenance(DatasetId datasetId, TrainingRunId trainingRunId, String baseModel,
                               Integer epochs, Instant trainedAt) {

    public ModelProvenance {
        if (baseModel != null && baseModel.isBlank()) {
            throw new IllegalArgumentException("ModelProvenance baseModel must not be blank when present");
        }
        if (epochs != null && epochs <= 0) {
            throw new IllegalArgumentException("ModelProvenance epochs must be positive when present: " + epochs);
        }
    }

    /**
     * The sentinel for "no provenance to report" — every field {@code null}. Used in place of a
     * {@code null} {@link ModelProvenance} reference so every {@link CvModelRecord} always has one
     * to call into.
     *
     * @return a provenance value with every field {@code null}
     */
    public static ModelProvenance none() {
        return new ModelProvenance(null, null, null, null, null);
    }
}
