package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.TrainingRunEntity;
import com.drones.vision.kernel.UserId;
import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.TrainingRunId;
import com.drones.vision.learning.domain.model.TrainingRunRecord;

/**
 * {@link TrainingRunRecord} &harr; {@link TrainingRunEntity} mapping
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.3, §5.3, CV-SETTINGS wave W3).
 */
public final class TrainingRunMapper {

    private TrainingRunMapper() {
    }

    /**
     * @param run the domain run to store
     * @return its entity
     */
    public static TrainingRunEntity toEntity(TrainingRunRecord run) {
        return new TrainingRunEntity(run.runId().value(), run.datasetId().value(), run.baseModel(), run.epochs(),
                run.state(), run.epoch(), run.totalEpochs(), run.loss(), run.map50(), run.outputModelId(),
                run.startedBy().value(), run.startedAt(), run.finishedAt(), run.message());
    }

    /**
     * @param entity the row to read
     * @return the domain run
     */
    public static TrainingRunRecord toDomain(TrainingRunEntity entity) {
        return new TrainingRunRecord(new TrainingRunId(entity.runId()), new DatasetId(entity.datasetId()),
                entity.baseModel(), entity.epochs(), entity.state(), entity.epoch(), entity.totalEpochs(),
                entity.loss(), entity.map50(), entity.outputModelId(), new UserId(entity.startedBy()),
                entity.startedAt(), entity.finishedAt(), entity.message());
    }
}
