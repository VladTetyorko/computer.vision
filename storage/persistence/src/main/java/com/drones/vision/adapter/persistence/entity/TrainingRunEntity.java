package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.learning.domain.model.JobState;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA row for {@code cv_training_runs} — mirrors {@link com.drones.vision.learning.domain.model.TrainingRunRecord}
 * field-for-field (docs/plans/active/CV-SETTINGS-PLAN.md §3.3, §5.3, {@code V30__cv_model_registry.sql}).
 * {@link com.drones.vision.adapter.persistence.mapper.TrainingRunMapper} owns the mapping both ways.
 *
 * <p>{@code id} is the domain's own {@code TrainingRunId}, not synthetic — same choice {@code
 * ControlProfileEntity}/{@code DatasetEntity} make for their own ids, since a run is referred to by
 * id across its whole lifetime (started → progressing → terminal). {@code state} reuses the domain
 * {@link JobState} enum directly in an {@code @Enumerated(EnumType.STRING)} field, same convention
 * as {@code DatasetEntity#status}. No FK to {@code cv_models}/{@code datasets} — same "no
 * cross-entity foreign keys" convention as the rest of this schema.
 */
@Entity
@Table(name = "cv_training_runs")
public class TrainingRunEntity {

    @Id
    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "base_model", nullable = false)
    private String baseModel;

    @Column(name = "epochs", nullable = false)
    private int epochs;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private JobState state;

    @Column(name = "epoch", nullable = false)
    private int epoch;

    @Column(name = "total_epochs", nullable = false)
    private int totalEpochs;

    @Column(name = "loss", nullable = false)
    private double loss;

    @Column(name = "map50", nullable = false)
    private double map50;

    @Column(name = "output_model_id")
    private String outputModelId;

    @Column(name = "started_by", nullable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "message", nullable = false)
    private String message;

    /** JPA only. */
    protected TrainingRunEntity() {
    }

    public TrainingRunEntity(UUID runId, UUID datasetId, String baseModel, int epochs, JobState state, int epoch,
                              int totalEpochs, double loss, double map50, String outputModelId, UUID startedBy,
                              Instant startedAt, Instant finishedAt, String message) {
        this.runId = runId;
        this.datasetId = datasetId;
        this.baseModel = baseModel;
        this.epochs = epochs;
        this.state = state;
        this.epoch = epoch;
        this.totalEpochs = totalEpochs;
        this.loss = loss;
        this.map50 = map50;
        this.outputModelId = outputModelId;
        this.startedBy = startedBy;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.message = message;
    }

    public UUID runId() {
        return runId;
    }

    public UUID datasetId() {
        return datasetId;
    }

    public String baseModel() {
        return baseModel;
    }

    public int epochs() {
        return epochs;
    }

    public JobState state() {
        return state;
    }

    public int epoch() {
        return epoch;
    }

    public int totalEpochs() {
        return totalEpochs;
    }

    public double loss() {
        return loss;
    }

    public double map50() {
        return map50;
    }

    public String outputModelId() {
        return outputModelId;
    }

    public UUID startedBy() {
        return startedBy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String message() {
        return message;
    }
}
