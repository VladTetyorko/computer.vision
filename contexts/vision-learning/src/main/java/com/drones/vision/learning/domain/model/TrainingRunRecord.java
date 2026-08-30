package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.UserId;

import java.time.Instant;

/**
 * A persisted training run (docs/plans/active/CV-SETTINGS-PLAN.md §3.3, §5.2 {@code TrainingRun}
 * wire shape) — the durable counterpart to the application layer's in-memory {@code
 * TrainingJobView} poll state (fixes H7: "training metrics evaporate — nothing is persisted"). One
 * row per training job: written when the job starts, updated as {@link TrainingProgress} messages
 * arrive (mirroring most of that record's fields, the same way {@code TrainingJobView} does), then
 * left as a permanent record once the job reaches a terminal {@link JobState}.
 *
 * <p>A run that reaches {@link JobState#SUCCEEDED} names the model it produced in {@link
 * #outputModelId()}. That model is registered in the catalogue as {@link ModelStatus#CANDIDATE},
 * never automatically {@link ModelStatus#LIVE} (docs/plans/active/CV-SETTINGS-PLAN.md §8 OQ6) — that
 * registration, and the {@link ModelProvenance} linking the new {@link CvModelRecord} back to this
 * run, are the application layer's job, not this record's.
 *
 * @param runId         typed run identity; must not be {@code null}
 * @param datasetId     the dataset this run trains on; must not be {@code null}
 * @param baseModel     the checkpoint this run started from; must not be blank
 * @param epochs        the requested total epoch count; must be positive
 * @param state         this run's current state; must not be {@code null}
 * @param epoch         the latest reported epoch; must not be negative, {@code 0} before any
 *                      progress arrives
 * @param totalEpochs   the latest reported total epoch count; must not be negative
 * @param loss          the latest reported training loss; meaningful only alongside {@link
 *                      JobState#RUNNING}
 * @param map50         the latest reported training mAP@0.5; meaningful only alongside {@link
 *                      JobState#RUNNING} — this is a training-time figure, not a held-out
 *                      evaluation (see {@link MetricsKind#TRAINING})
 * @param outputModelId the model id this run produced, or {@code null} until/unless it succeeds;
 *                      must not be blank when present
 * @param startedBy     who started this run; must not be {@code null}
 * @param startedAt     when this run started; must not be {@code null}
 * @param finishedAt    when this run reached a terminal state, or {@code null} while still {@link
 *                      JobState#RUNNING}
 * @param message       free-form status text; may be blank, never {@code null} — mirrors {@link
 *                      TrainingProgress#message()}
 */
public record TrainingRunRecord(TrainingRunId runId, DatasetId datasetId, String baseModel, int epochs,
                                 JobState state, int epoch, int totalEpochs, double loss, double map50,
                                 String outputModelId, UserId startedBy, Instant startedAt,
                                 Instant finishedAt, String message) {

    public TrainingRunRecord {
        if (runId == null) {
            throw new IllegalArgumentException("TrainingRunRecord runId must not be null");
        }
        if (datasetId == null) {
            throw new IllegalArgumentException("TrainingRunRecord datasetId must not be null");
        }
        if (baseModel == null || baseModel.isBlank()) {
            throw new IllegalArgumentException("TrainingRunRecord baseModel must not be blank");
        }
        if (epochs <= 0) {
            throw new IllegalArgumentException("TrainingRunRecord epochs must be positive: " + epochs);
        }
        if (state == null) {
            throw new IllegalArgumentException("TrainingRunRecord state must not be null");
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("TrainingRunRecord epoch must not be negative: " + epoch);
        }
        if (totalEpochs < 0) {
            throw new IllegalArgumentException(
                    "TrainingRunRecord totalEpochs must not be negative: " + totalEpochs);
        }
        if (outputModelId != null && outputModelId.isBlank()) {
            throw new IllegalArgumentException(
                    "TrainingRunRecord outputModelId must not be blank when present");
        }
        if (startedBy == null) {
            throw new IllegalArgumentException("TrainingRunRecord startedBy must not be null");
        }
        if (startedAt == null) {
            throw new IllegalArgumentException("TrainingRunRecord startedAt must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("TrainingRunRecord message must not be null");
        }
    }
}
