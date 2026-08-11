package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.model.Verification;

/**
 * {@link Mark} ⟷ {@link MarkEntity} (docs/plans/done/TACTICAL-MARKS-PLAN.md §3, reworked by
 * docs/plans/done/MAP-REWORK-PLAN.md §4.4).
 *
 * <p>Three value objects are flattened rather than nested, matching the columns {@code
 * V10__marks.sql}/{@code V12__map_layers.sql} define: {@code position} into
 * latitude/longitude/altitude, {@code ownership} into owner/group, and — added by the rework —
 * {@code verification} into state/verifiedBy/verifiedAt. The nullable {@code verifiedBy}/{@code
 * verifiedAt} pair round-trips as-is; {@link Verification}'s own compact constructor is what
 * re-asserts "non-null exactly when CONFIRMED/REJECTED" on the way back, so a row violating that
 * invariant fails loudly here rather than yielding a silently-inconsistent domain object.
 */
public final class MarkMapper {

    private MarkMapper() {
    }

    public static MarkEntity toEntity(Mark mark) {
        GeoPosition position = mark.position();
        Verification verification = mark.verification();
        return new MarkEntity(mark.id().value(), mark.layerId().value(), mark.kind(), mark.affiliation(),
                mark.label(), mark.note(), position.latitude(), position.longitude(), position.altitudeMeters(),
                mark.ownership().ownerId().value(), mark.ownership().groupId().value(),
                mark.createdAt(), mark.status(), mark.source(), verification.state(),
                verification.verifiedBy() == null ? null : verification.verifiedBy().value(),
                verification.verifiedAt());
    }

    public static Mark toDomain(MarkEntity entity) {
        GeoPosition position = new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        Verification verification = new Verification(entity.verificationState(),
                entity.verifiedBy() == null ? null : new UserId(entity.verifiedBy()), entity.verifiedAt());
        return new Mark(new MarkId(entity.id()), new LayerId(entity.layerId()), position, entity.kind(),
                entity.affiliation(), entity.label(), entity.note(), ownership, entity.createdAt(),
                entity.status(), entity.source(), verification);
    }
}
