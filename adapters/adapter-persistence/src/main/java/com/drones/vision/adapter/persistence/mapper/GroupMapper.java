package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.GroupEntity;
import com.drones.vision.domain.model.Group;
import com.drones.vision.domain.model.GroupId;

/**
 * {@link Group} &harr; {@link GroupEntity} mapping, extracted from {@code JpaGroupRepository}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class GroupMapper {

    private GroupMapper() {
    }

    public static GroupEntity toEntity(Group group) {
        return new GroupEntity(group.id().value(), group.name(),
                group.parentGroupId() == null ? null : group.parentGroupId().value());
    }

    public static Group toDomain(GroupEntity entity) {
        return new Group(new GroupId(entity.id()), entity.name(),
                entity.parentId() == null ? null : new GroupId(entity.parentId()));
    }
}
