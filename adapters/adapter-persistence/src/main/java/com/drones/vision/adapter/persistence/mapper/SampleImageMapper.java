package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.SampleImageEntity;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.TrainingSampleId;

import java.time.Instant;

/**
 * {@link SampleImage} &harr; {@link SampleImageEntity} mapping, extracted from {@code
 * JpaSampleImageStore} (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class SampleImageMapper {

    private SampleImageMapper() {
    }

    public static SampleImageEntity toEntity(TrainingSampleId id, SampleImage image) {
        return new SampleImageEntity(id.value(), image.contentType(), image.data(), Instant.now());
    }

    public static SampleImage toDomain(SampleImageEntity entity) {
        return new SampleImage(entity.data(), entity.contentType());
    }
}
