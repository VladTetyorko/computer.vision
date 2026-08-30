package com.drones.vision.api.dto;

import com.drones.vision.learning.domain.model.ModelProvenance;

/**
 * {@code CvModelResponse#provenance} — training lineage for a registry roster entry
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.2). No {@code @JsonInclude(NON_NULL)} here (unlike the
 * enclosing {@link CvModelResponse}, which omits this whole object when {@code null}): every field
 * below serializes as explicit {@code null} — not merely absent — for a config-seeded model that was
 * never trained through this platform's loop ({@link ModelProvenance#none()}), matching the frozen TS
 * {@code CvModelProvenance} interface's own "every field is {@code null}, not merely absent" contract.
 *
 * @param datasetId     the dataset trained on, as its canonical UUID string, or {@code null}
 * @param trainingRunId the training run that produced this model, as its canonical UUID string, or
 *                       {@code null}
 * @param baseModel     the checkpoint the run started from, or {@code null}
 * @param epochs        epochs trained for, or {@code null}
 * @param trainedAt     when training finished, or {@code null}
 */
public record CvModelProvenanceResponse(String datasetId, String trainingRunId, String baseModel, Integer epochs,
                                         java.time.Instant trainedAt) {

    /**
     * @param provenance the domain provenance; must not be {@code null} — pass {@link
     *                    ModelProvenance#none()} for "nothing to report", not a {@code null}
     *                    reference (mirrors that type's own "never {@code null}" contract)
     * @return the wire representation, every field {@code null} when {@code provenance} carries none
     */
    public static CvModelProvenanceResponse from(ModelProvenance provenance) {
        return new CvModelProvenanceResponse(
                provenance.datasetId() == null ? null : provenance.datasetId().value().toString(),
                provenance.trainingRunId() == null ? null : provenance.trainingRunId().value().toString(),
                provenance.baseModel(), provenance.epochs(), provenance.trainedAt());
    }
}
