package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.CvModelEntity;
import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.TrainingRunId;

/**
 * {@link CvModelRecord} &harr; {@link CvModelEntity} mapping (docs/plans/active/CV-SETTINGS-PLAN.md
 * §5.3, CV-SETTINGS wave W3).
 *
 * <p>{@link com.drones.vision.learning.domain.model.ModelProvenance} decomposes into five flat
 * columns (see {@link CvModelEntity}'s own javadoc) and is reassembled here on read; every field is
 * independently nullable, matching {@code ModelProvenance}'s own contract.
 */
public final class CvModelMapper {

    private CvModelMapper() {
    }

    /**
     * @param model the domain row to store
     * @return its entity
     */
    public static CvModelEntity toEntity(CvModelRecord model) {
        ModelProvenance provenance = model.provenance();
        return new CvModelEntity(model.modelId(), model.version(), model.displayName(), model.kind(),
                model.openVocab(), model.defaultLabelFilter(), model.taskType(), model.runtime(), model.classes(),
                model.status(), model.metrics(),
                provenance.datasetId() == null ? null : provenance.datasetId().value(),
                provenance.trainingRunId() == null ? null : provenance.trainingRunId().value(),
                provenance.baseModel(), provenance.epochs(), provenance.trainedAt(),
                model.promotedBy() == null ? null : model.promotedBy().value(), model.promotedAt(),
                model.createdAt());
    }

    /**
     * @param entity the row to read
     * @return the domain row
     */
    public static CvModelRecord toDomain(CvModelEntity entity) {
        ModelProvenance provenance = new ModelProvenance(
                entity.datasetId() == null ? null : new DatasetId(entity.datasetId()),
                entity.trainingRunId() == null ? null : new TrainingRunId(entity.trainingRunId()),
                entity.baseModel(), entity.epochs(), entity.trainedAt());
        return new CvModelRecord(entity.modelId(), entity.version(), entity.displayName(), entity.kind(),
                entity.openVocab(), entity.defaultLabelFilter(), entity.taskType(), entity.runtime(),
                entity.classes(), entity.status(), entity.metrics(), provenance,
                entity.promotedBy() == null ? null : new UserId(entity.promotedBy()), entity.promotedAt(),
                entity.createdAt());
    }
}
