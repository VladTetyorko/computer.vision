package com.drones.vision.domain.model;

/**
 * One message in a training job's progress stream (docs/plans/done/CV-TRAINING-PLAN.md §6/§7, Phase 2) — the
 * Java-side shape of {@code cv.proto}'s {@code Training.StartTraining} response message,
 * {@code TrainingProgress}.
 *
 * <p>cv-service's contract: every message on the stream carries the same {@code jobId}; one
 * {@link JobState#RUNNING} message per epoch (1-based {@code epoch}, plus {@code totalEpochs},
 * {@code loss}, {@code map50} for that epoch); then exactly one terminal
 * {@link JobState#SUCCEEDED} (whose {@code message} names the produced model id) or
 * {@link JobState#FAILED} (whose {@code message} is the failure reason, e.g. a missing dataset).
 * Cancellation simply ends the stream with no terminal message — see {@link TrainingPort} for how a
 * caller observes that.
 *
 * <p>{@code jobId} is a plain string, not a typed id — it is opaque, assigned by the training host
 * on the far side of the gRPC boundary, mirroring the wire message field-for-field (same reasoning
 * as {@link TrainingJobSpec#datasetId()}).
 *
 * @param jobId       the job this message belongs to; must not be blank
 * @param epoch       1-based epoch number this message reports on; must not be negative (0 is valid
 *                    for a terminal message with no epoch of its own to report)
 * @param totalEpochs the job's total epoch count; must not be negative
 * @param loss        training loss for this epoch; meaningful only alongside {@link JobState#RUNNING}
 * @param map50       mAP@0.5 for this epoch; meaningful only alongside {@link JobState#RUNNING}
 * @param state       this message's job state
 * @param message     free-form text: the produced model id on {@link JobState#SUCCEEDED}, the
 *                    failure reason on {@link JobState#FAILED}, empty otherwise; may be empty, never
 *                    {@code null}
 */
public record TrainingProgress(String jobId, int epoch, int totalEpochs, double loss, double map50,
                                JobState state, String message) {

    public TrainingProgress {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("TrainingProgress jobId must not be blank");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("TrainingProgress epoch must not be negative: " + epoch);
        }
        if (totalEpochs < 0) {
            throw new IllegalArgumentException(
                    "TrainingProgress totalEpochs must not be negative: " + totalEpochs);
        }
        if (state == null) {
            throw new IllegalArgumentException("TrainingProgress state must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("TrainingProgress message must not be null");
        }
    }
}
