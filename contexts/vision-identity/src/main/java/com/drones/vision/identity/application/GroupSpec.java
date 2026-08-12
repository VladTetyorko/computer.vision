package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Group;
import com.drones.vision.kernel.GroupId;

/**
 * Everything needed to create a {@link Group} (docs/plans/done/U-AUTH-PLAN.md, wave 2) — a top-level record
 * rather than a type nested in {@link GroupService}, same reasoning as {@link UserSpec}.
 *
 * @param name          human-readable name; must not be blank
 * @param parentGroupId the enclosing group, or {@code null} to create a root group
 */
public record GroupSpec(String name, GroupId parentGroupId) {

    public GroupSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GroupSpec name must not be blank");
        }
    }
}
