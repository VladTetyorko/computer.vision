package com.drones.vision.domain.model;

/**
 * A request to fine-tune a CV model (docs/plans/done/CV-TRAINING-PLAN.md §6/§7, Phase 2) — the Java-side shape
 * of {@code cv.proto}'s {@code Training.StartTraining} request message, {@code TrainingJobSpec}.
 *
 * <p>{@code datasetId} is deliberately a plain string, not a typed {@link DatasetId} — it crosses a
 * gRPC boundary to a training host that has no notion of the platform's id types, mirroring the wire
 * message field-for-field (same reasoning as {@link ModelRef}'s plain-string {@code id}).
 *
 * @param baseModel the model id to fine-tune from; must not be blank
 * @param datasetId the id of the exported {@link Dataset} to train on; must not be blank
 * @param epochs    number of training epochs to run; must be positive
 */
public record TrainingJobSpec(String baseModel, String datasetId, int epochs) {

    public TrainingJobSpec {
        if (baseModel == null || baseModel.isBlank()) {
            throw new IllegalArgumentException("TrainingJobSpec baseModel must not be blank");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("TrainingJobSpec datasetId must not be blank");
        }
        if (epochs <= 0) {
            throw new IllegalArgumentException("TrainingJobSpec epochs must be positive: " + epochs);
        }
    }
}
