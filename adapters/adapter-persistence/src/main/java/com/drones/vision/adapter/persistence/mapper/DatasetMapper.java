package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.DatasetEntity;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Dataset;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * {@link Dataset} &harr; {@link DatasetEntity} mapping, extracted from {@code
 * JpaDatasetRepository} (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class DatasetMapper {

    private DatasetMapper() {
    }

    public static DatasetEntity toEntity(Dataset dataset) {
        CategoryId targetCategory = dataset.targetCategory();
        return new DatasetEntity(dataset.id().value(), dataset.name(),
                targetCategory == null ? null : targetCategory.slug(), dataset.classes(),
                dataset.ownership().ownerId().value(), dataset.ownership().groupId().value(),
                dataset.status(), dataset.createdAt());
    }

    public static Dataset toDomain(DatasetEntity entity) {
        String targetCategory = entity.targetCategory();
        return new Dataset(new DatasetId(entity.id()), entity.name(),
                targetCategory == null ? null : new CategoryId(targetCategory), entity.classes(),
                new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId())),
                entity.status(), entity.createdAt());
    }
}
