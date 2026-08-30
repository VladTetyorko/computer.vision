package com.drones.vision.api.dto;

import com.drones.vision.learning.domain.model.TrainingRunRecord;

import java.time.Instant;

/**
 * Wire representation of a persisted {@link TrainingRunRecord} (docs/plans/active/CV-SETTINGS-PLAN.md
 * &sect;5.2's frozen wire contract) — a {@code vision-learning} training run's durable record,
 * distinct from the older, in-memory-only {@link TrainingJobResponse} (see that class's own
 * javadoc: this is the {@code GET /api/cv/training/runs}[/{runId}] surface, unbounded by {@link
 * com.drones.vision.learning.application.TrainingJobService}'s finished-job retention policy).
 * Reuses {@link com.drones.vision.learning.domain.model.JobState}'s own three names for {@code
 * state} verbatim — no translation needed, unlike {@code ModelStatus}/{@code ModelTaskType}.
 *
 * <p><b>Deviations from {@link TrainingRunRecord}, both informational/deferred — flagged in the
 * W5 handoff, not fixed here (the domain record is committed, read-only for this wave):</b>
 * <ul>
 *   <li>{@code message} is dropped — {@code models.ts}'s frozen {@code TrainingRun} interface has
 *       no such field (unlike {@link TrainingJobResponse#message()}, which does carry one).</li>
 *   <li>{@code loss}/{@code map50} are boxed {@link Double}, always populated from the domain's
 *       primitive {@code double} fields, never actually {@code null} from this mapping — but
 *       {@code models.ts} declares both {@code number | null}. The domain has no way to distinguish
 *       "no progress reported yet" from a genuine {@code 0.0}, so this mapping cannot honor the
 *       nullable half of the wire contract; a future wave adding that distinction to {@link
 *       TrainingRunRecord} would need to touch {@code vision-learning}, out of this wave's write
 *       scope.</li>
 * </ul>
 *
 * @param runId         the run id, as a canonical UUID string
 * @param datasetId     the dataset this run trained on, as a canonical UUID string
 * @param datasetName   the dataset's human-readable name, resolved by {@code TrainingJobController}
 *                      via {@code DatasetRepositoryPort} (not carried by {@link TrainingRunRecord}
 *                      itself) — falls back to {@code datasetId} when the dataset was deleted
 *                      after the run started, the same "fall back to the id" idiom {@link
 *                      CvCoverageRowResponse#from}/{@code DefaultAssetService#toSummary} both take
 *                      for a stale reference
 * @param baseModel     the checkpoint this run started from
 * @param epochs        the requested total epoch count
 * @param state         {@code "RUNNING"}/{@code "SUCCEEDED"}/{@code "FAILED"} —
 *                      {@link com.drones.vision.learning.domain.model.JobState#name()} verbatim
 * @param epoch         the latest reported epoch
 * @param totalEpochs   the latest reported total epoch count
 * @param loss          the latest reported training loss; never {@code null} — see the deviation
 *                      note above
 * @param map50         the latest reported training mAP@0.5; never {@code null} — see the deviation
 *                      note above
 * @param outputModelId the model id this run produced, or {@code null} until/unless it succeeds
 * @param startedAt     when this run started
 * @param finishedAt    when this run reached a terminal state, or {@code null} while still running
 * @param startedBy     who started this run, as a canonical UUID string
 */
public record TrainingRunResponse(String runId, String datasetId, String datasetName, String baseModel, int epochs,
                                   String state, int epoch, int totalEpochs, Double loss, Double map50,
                                   String outputModelId, Instant startedAt, Instant finishedAt, String startedBy) {

    /**
     * Maps a persisted training run to the wire, field for field.
     *
     * @param run         the run to map
     * @param datasetName the run's dataset's human-readable name, resolved by the caller
     * @return the response body for {@code run}
     */
    public static TrainingRunResponse from(TrainingRunRecord run, String datasetName) {
        return new TrainingRunResponse(run.runId().value().toString(), run.datasetId().value().toString(),
                datasetName, run.baseModel(), run.epochs(), run.state().name(), run.epoch(), run.totalEpochs(),
                run.loss(), run.map50(), run.outputModelId(), run.startedAt(), run.finishedAt(),
                run.startedBy().value().toString());
    }
}
