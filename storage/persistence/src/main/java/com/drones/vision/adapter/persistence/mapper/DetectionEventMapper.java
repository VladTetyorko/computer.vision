package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.DetectionEventEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.model.DetectionEventId;

/**
 * {@link DetectionEvent} &harr; {@link DetectionEventEntity} mapping, following the extraction
 * convention every other aggregate's mapper in this package already uses
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class DetectionEventMapper {

    private DetectionEventMapper() {
    }

    public static DetectionEventEntity toEntity(DetectionEvent event) {
        GeoPosition position = event.position();
        return new DetectionEventEntity(event.id().value(), event.streamId().value(),
                event.assetId() == null ? null : event.assetId().value(), event.label(), event.peakConfidence(),
                event.firstSeen(), event.lastSeen(), event.state(),
                position == null ? null : position.latitude(), position == null ? null : position.longitude(),
                position == null ? null : position.altitudeMeters());
    }

    public static DetectionEvent toDomain(DetectionEventEntity entity) {
        GeoPosition position = entity.positionLatitude() == null ? null
                : new GeoPosition(entity.positionLatitude(), entity.positionLongitude(),
                        entity.positionAltitudeMeters());
        return new DetectionEvent(new DetectionEventId(entity.id()), new StreamId(entity.streamId()),
                entity.assetId() == null ? null : new AssetId(entity.assetId()), entity.label(),
                entity.peakConfidence(), entity.firstSeen(), entity.lastSeen(), entity.state(), position);
    }
}
