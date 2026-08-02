package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

/**
 * {@link Mark} &harr; {@link MarkEntity} mapping, extracted from {@code JpaMarkRepository}
 * (docs/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class MarkMapper {

    private MarkMapper() {
    }

    public static MarkEntity toEntity(Mark mark) {
        GeoPosition position = mark.position();
        return new MarkEntity(mark.id().value(), mark.kind(), mark.label(), mark.note(),
                position.latitude(), position.longitude(), position.altitudeMeters(),
                mark.ownership().ownerId().value(), mark.ownership().groupId().value(),
                mark.createdAt(), mark.status(), mark.source());
    }

    public static Mark toDomain(MarkEntity entity) {
        GeoPosition position = new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        return new Mark(new MarkId(entity.id()), position, entity.kind(), entity.label(), entity.note(),
                ownership, entity.createdAt(), entity.status(), entity.source());
    }
}
