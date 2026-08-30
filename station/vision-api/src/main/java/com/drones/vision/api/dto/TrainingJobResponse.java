package com.drones.vision.api.dto;

import com.drones.vision.learning.application.TrainingJobView;

import java.time.Instant;

/**
 * Wire representation of a {@link TrainingJobView} (docs/plans/done/CV-TRAINING-PLAN.md §8's frozen wire
 * contract) — the flattened, pollable state of one CV fine-tune job. Body of {@code POST
 * /api/datasets/{id}/train} and {@code GET /api/training/jobs}[/{jobId}].
 *
 * <p>No {@code @JsonInclude(NON_NULL)} here — every {@link TrainingJobView} field is always
 * present (its own compact constructor guarantees non-null/non-blank values, defaulting to {@code
 * 0}/{@code ""} before the first progress message arrives), the same "no nullable fields" posture
 * {@link PromoteModelRequest} takes.
 *
 * @param jobId       the id {@code POST /api/datasets/{id}/train} returned; poll it via {@code GET
 *                    /api/training/jobs/{jobId}}
 * @param baseModel   the checkpoint the job started from
 * @param datasetId   the dataset the job trains against
 * @param epochs      the requested total epoch count
 * @param epoch       the latest reported epoch; {@code 0} before any progress message arrives
 * @param totalEpochs the latest reported total epoch count; {@code 0} before the first message
 * @param loss        the latest reported loss
 * @param map50       the latest reported mAP@50
 * @param state       {@code RUNNING}/{@code SUCCEEDED}/{@code FAILED} — {@link
 *                    com.drones.vision.learning.domain.model.JobState#name()} verbatim; a training failure
 *                    is reported here, never as an HTTP error
 * @param message     free-form text — {@code ""} before the first progress message, the produced
 *                    model id on success, the failure reason on {@code FAILED}
 * @param startedAt   when the job was accepted
 */
public record TrainingJobResponse(String jobId, String baseModel, String datasetId, int epochs, int epoch,
                                   int totalEpochs, double loss, double map50, String state, String message,
                                   Instant startedAt) {

    /**
     * Maps a domain {@link TrainingJobView} to its wire representation.
     *
     * @param view the job state to map
     * @return the response body for {@code view}
     */
    public static TrainingJobResponse from(TrainingJobView view) {
        return new TrainingJobResponse(view.jobId(), view.baseModel(), view.datasetId(), view.epochs(),
                view.epoch(), view.totalEpochs(), view.loss(), view.map50(), view.state().name(), view.message(),
                view.startedAt());
    }
}
