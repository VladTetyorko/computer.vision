package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.MapDrawingEntity;
import com.drones.vision.domain.model.Drawing;
import com.drones.vision.domain.model.DrawingId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * {@link Drawing} ⟷ {@link MapDrawingEntity} (docs/plans/done/MAP-REWORK-PLAN.md §4.4).
 *
 * <p>{@code points} passes straight through as the domain {@code List<GeoPosition>} — Hibernate's
 * Jackson-backed {@code FormatMapper} serializes the record list into the jsonb column with no
 * adapter-local shape in between, the same convention {@code GeofenceZoneMapper} follows for a
 * zone's polygon.
 */
public final class DrawingMapper {

    private DrawingMapper() {
    }

    public static MapDrawingEntity toEntity(Drawing drawing) {
        return new MapDrawingEntity(drawing.id().value(), drawing.layerId().value(), drawing.kind(),
                drawing.label(), drawing.colorToken(), drawing.points(),
                drawing.ownership().ownerId().value(), drawing.ownership().groupId().value(),
                drawing.createdAt());
    }

    public static Drawing toDomain(MapDrawingEntity entity) {
        Ownership ownership = new Ownership(new UserId(entity.ownerUserId()), new GroupId(entity.groupId()));
        return new Drawing(new DrawingId(entity.id()), new LayerId(entity.layerId()), entity.kind(),
                entity.points(), entity.label(), entity.colorToken(), ownership, entity.createdAt());
    }
}
