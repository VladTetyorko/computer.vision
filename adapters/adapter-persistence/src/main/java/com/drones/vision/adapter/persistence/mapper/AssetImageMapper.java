package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetImageEntity;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetImage;

import java.time.Instant;

/**
 * {@link AssetImage} &harr; {@link AssetImageEntity} mapping, extracted from {@code
 * JpaAssetImageRepository} (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class AssetImageMapper {

    private AssetImageMapper() {
    }

    public static AssetImageEntity toEntity(AssetId assetId, AssetImage image) {
        return new AssetImageEntity(assetId.value(), image.contentType(), image.data(), Instant.now());
    }

    public static AssetImage toDomain(AssetImageEntity entity) {
        return new AssetImage(entity.data(), entity.contentType());
    }
}
