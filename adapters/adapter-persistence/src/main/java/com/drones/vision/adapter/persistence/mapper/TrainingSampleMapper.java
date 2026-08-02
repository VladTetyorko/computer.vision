package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.TrainingSampleEntity;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;

import java.util.UUID;

/**
 * {@link TrainingSample} &harr; {@link TrainingSampleEntity} mapping, extracted from {@code
 * JpaTrainingSampleRepository} (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class TrainingSampleMapper {

    private TrainingSampleMapper() {
    }

    public static TrainingSampleEntity toEntity(TrainingSample sample) {
        AssetId assetId = sample.assetId();
        UserId labeledBy = sample.labeledBy();
        return new TrainingSampleEntity(sample.id().value(), sample.datasetId().value(),
                sample.streamId().value(), assetId == null ? null : assetId.value(), sample.capturedAt(),
                sample.width(), sample.height(), sample.annotations(), sample.status(),
                labeledBy == null ? null : labeledBy.value(), sample.labeledAt());
    }

    public static TrainingSample toDomain(TrainingSampleEntity entity) {
        UUID assetId = entity.assetId();
        UUID labeledBy = entity.labeledBy();
        return new TrainingSample(new TrainingSampleId(entity.id()), new DatasetId(entity.datasetId()),
                new StreamId(entity.streamId()), assetId == null ? null : new AssetId(assetId),
                entity.capturedAt(), entity.width(), entity.height(), entity.annotations(), entity.status(),
                labeledBy == null ? null : new UserId(labeledBy), entity.labeledAt());
    }
}
