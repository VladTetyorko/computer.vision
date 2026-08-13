package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;

/**
 * {@link AssetUsage} &harr; {@link AssetUsageEntity} mapping, extracted from {@code
 * JpaAssetUsageRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class AssetUsageMapper {

    private AssetUsageMapper() {
    }

    public static AssetUsageEntity toEntity(AssetUsage usage) {
        GeoPosition start = usage.startPosition();
        GeoPosition last = usage.lastPosition();
        StreamId streamId = usage.streamId();
        return new AssetUsageEntity(usage.id().value(), usage.assetId().value(), usage.startedAt(), usage.endedAt(),
                start == null ? null : start.latitude(), start == null ? null : start.longitude(),
                start == null ? null : start.altitudeMeters(),
                last == null ? null : last.latitude(), last == null ? null : last.longitude(),
                last == null ? null : last.altitudeMeters(),
                usage.sampleCount(), streamId == null ? null : streamId.value());
    }

    public static AssetUsage toDomain(AssetUsageEntity entity) {
        GeoPosition start = entity.startLatitude() == null ? null
                : new GeoPosition(entity.startLatitude(), entity.startLongitude(), entity.startAltitudeMeters());
        GeoPosition last = entity.lastLatitude() == null ? null
                : new GeoPosition(entity.lastLatitude(), entity.lastLongitude(), entity.lastAltitudeMeters());
        StreamId streamId = entity.streamId() == null ? null : new StreamId(entity.streamId());
        return new AssetUsage(new UsageId(entity.id()), new AssetId(entity.assetId()), entity.startedAt(),
                entity.endedAt(), start, last, entity.sampleCount(), streamId);
    }
}
