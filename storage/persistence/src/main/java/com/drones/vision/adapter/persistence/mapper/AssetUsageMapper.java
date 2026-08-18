package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UsageId;

/**
 * {@link AssetUsage} &harr; {@link AssetUsageEntity} mapping, extracted from {@code
 * JpaAssetUsageRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 *
 * <p>{@code phase} (docs/plans/active/DRONE-ONBOARDING-PLAN.md §2.3, Wave O5/O7) always round-trips
 * on the way in: {@link AssetUsage#phase()} is non-null by the domain record's own compact
 * constructor, so {@link #toEntity} always writes a real value. On the way back, a {@code null}
 * entity column (a row saved before this column existed) is honestly mapped through {@link
 * AssetUsage}'s own pre-O7 8-arg convenience constructor, which defaults to {@link
 * UsagePhase#PREFLIGHT} — see {@code AssetUsageEntity}'s own javadoc for why this is the correct
 * "unknown, not fabricated" fallback rather than a bug.
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
                usage.sampleCount(), streamId == null ? null : streamId.value(), usage.phase());
    }

    public static AssetUsage toDomain(AssetUsageEntity entity) {
        GeoPosition start = entity.startLatitude() == null ? null
                : new GeoPosition(entity.startLatitude(), entity.startLongitude(), entity.startAltitudeMeters());
        GeoPosition last = entity.lastLatitude() == null ? null
                : new GeoPosition(entity.lastLatitude(), entity.lastLongitude(), entity.lastAltitudeMeters());
        StreamId streamId = entity.streamId() == null ? null : new StreamId(entity.streamId());
        UsageId id = new UsageId(entity.id());
        AssetId assetId = new AssetId(entity.assetId());
        return entity.phase() == null
                ? new AssetUsage(id, assetId, entity.startedAt(), entity.endedAt(), start, last,
                        entity.sampleCount(), streamId)
                : new AssetUsage(id, assetId, entity.startedAt(), entity.endedAt(), start, last,
                        entity.sampleCount(), streamId, entity.phase());
    }
}
