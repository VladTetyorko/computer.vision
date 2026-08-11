package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.GeofenceZoneEntity;
import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.ZoneId;

/**
 * {@link GeofenceZone} &harr; {@link GeofenceZoneEntity} mapping, extracted from {@code
 * JpaGeofenceRepository} (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class GeofenceZoneMapper {

    private GeofenceZoneMapper() {
    }

    public static GeofenceZoneEntity toEntity(GeofenceZone zone) {
        return new GeofenceZoneEntity(zone.id().value(), zone.name(), zone.kind(), zone.polygon(),
                zone.maxAltitudeMeters(), zone.enabled());
    }

    public static GeofenceZone toDomain(GeofenceZoneEntity entity) {
        return new GeofenceZone(new ZoneId(entity.id()), entity.name(), entity.kind(), entity.polygon(),
                entity.maxAltitudeMeters(), entity.enabled());
    }
}
