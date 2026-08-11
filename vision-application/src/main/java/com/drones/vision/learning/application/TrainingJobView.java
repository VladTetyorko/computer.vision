package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.JobState;
import com.drones.vision.learning.domain.model.TrainingJobSpec;
import com.drones.vision.learning.domain.model.TrainingProgress;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@link TrainingJobService#jobs()}/{@link TrainingJobService#job(String)} — the
 * pollable state of one training job (docs/plans/done/CV-TRAINING-PLAN.md §6/§7, Phase 2).
 *
 * <p>{@code jobId} is the id {@link TrainingJobService#start} generated and returned to the
 * caller, <b>not</b> necessarily the {@code jobId} the wire {@link TrainingProgress} messages
 * carry — cv-service assigns its own; see {@link DefaultTrainingJobService}'s own javadoc for why
 * the two are allowed to differ and why only this one is ever exposed here. {@code
 * baseModel}/{@code datasetId}/{@code epochs} are copied once from the {@link TrainingJobSpec} the
 * job was started with and never change again; {@code epoch}/{@code totalEpochs}/{@code
 * loss}/{@code map50}/{@code state}/{@code message} are the latest {@link TrainingProgress}
 * observed so far — all at their "nothing has arrived yet" values ({@code 0}, {@code 0.0}, {@link
 * JobState#RUNNING}, {@code ""}) the moment {@link TrainingJobService#start} returns.
 *
 * @param jobId       the locally generated id a poller uses to look this job up
 * @param baseModel   the checkpoint the job started from
 * @param datasetId   the dataset the job trains against
 * @param epochs      the requested total epoch count
 * @param epoch       the latest reported epoch; {@code 0} before any progress message arrives
 * @param totalEpochs the latest reported total epoch count (mirrors {@code epochs} once training
 *                    actually reports progress); {@code 0} before the first message
 * @param loss        the latest reported loss; meaningful only alongside {@link JobState#RUNNING}
 * @param map50       the latest reported mAP@50; meaningful only alongside {@link JobState#RUNNING}
 * @param state       the job's current state
 * @param message     free-form text — {@code ""} before the first progress message, the produced
 *                    model id on {@link JobState#SUCCEEDED}, the failure reason on {@link
 *                    JobState#FAILED} (including a transport error {@link
 *                    DefaultTrainingJobService} caught, in which case this is the exception's own
 *                    message)
 * @param startedAt   when {@link TrainingJobService#start} accepted this job
 */
public record TrainingJobView(String jobId, String baseModel, String datasetId, int epochs, int epoch,
                               int totalEpochs, double loss, double map50, JobState state, String message,
                               Instant startedAt) {

    public TrainingJobView {
        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("TrainingJobView jobId must not be blank");
        }
        if (baseModel == null || baseModel.isBlank()) {
            throw new IllegalArgumentException("TrainingJobView baseModel must not be blank");
        }
        if (datasetId == null || datasetId.isBlank()) {
            throw new IllegalArgumentException("TrainingJobView datasetId must not be blank");
        }
        if (epochs <= 0) {
            throw new IllegalArgumentException("TrainingJobView epochs must be positive: " + epochs);
        }
        if (epoch < 0) {
            throw new IllegalArgumentException("TrainingJobView epoch must not be negative: " + epoch);
        }
        if (totalEpochs < 0) {
            throw new IllegalArgumentException("TrainingJobView totalEpochs must not be negative: " + totalEpochs);
        }
        Objects.requireNonNull(state, "TrainingJobView state must not be null");
        Objects.requireNonNull(message, "TrainingJobView message must not be null");
        Objects.requireNonNull(startedAt, "TrainingJobView startedAt must not be null");
    }
}
