package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.TrackPointEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.TrackPoint;

/**
 * {@link TrackPoint} ⟷ {@link TrackPointEntity} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md decision D3/§7).
 *
 * <p>{@code toEntity} never carries an {@code id} — {@link TrackPoint} has none of its own, and
 * {@link TrackPointEntity#id()} is assigned by the database on insert (see that entity's own
 * javadoc), so there is nothing to pass through on the way in. {@code position} is flattened
 * rather than nested, the same choice {@link CameraPoseMapper} makes for its own position.
 */
public final class TrackPointMapper {

    private TrackPointMapper() {
    }

    public static TrackPointEntity toEntity(TrackPoint point) {
        GeoPosition position = point.position();
        return new TrackPointEntity(point.assetId().value(), point.trackId(), point.label(),
                point.layerId().value(), position.latitude(), position.longitude(), position.altitudeMeters(),
                point.errorRadiusMeters(), point.capturedAt());
    }

    public static TrackPoint toDomain(TrackPointEntity entity) {
        GeoPosition position = new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        return new TrackPoint(new AssetId(entity.assetId()), entity.trackId(), entity.label(),
                new LayerId(entity.layerId()), position, entity.errorRadiusMeters(), entity.capturedAt());
    }
}
