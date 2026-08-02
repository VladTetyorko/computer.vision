package com.drones.vision.api.dto;

import com.drones.vision.application.identity.GroupSpec;
import com.drones.vision.domain.model.GroupId;

/**
 * Request body for {@code POST /api/groups} (docs/U-SCOPE-PLAN.md, U-e slice 2) — create a group,
 * optionally under a parent. Converts to {@link GroupSpec} via {@link #toSpec()}.
 *
 * <p>Blank-name validation is left to {@link GroupSpec}'s own compact constructor (surfacing as
 * {@code 400}); only {@code parentGroupId}'s UUID parse happens here, via {@code GroupId.of} — a
 * malformed value is {@code 400}, the same id-parsing idiom as every other spot in this module.
 *
 * @param name          human-readable name
 * @param parentGroupId the enclosing group's id, or {@code null}/absent to create a root group
 */
public record CreateGroupRequest(String name, String parentGroupId) {

    /**
     * Converts to the application-layer {@link GroupSpec}.
     *
     * @return the spec to create
     */
    public GroupSpec toSpec() {
        GroupId parent = (parentGroupId == null || parentGroupId.isBlank()) ? null : GroupId.of(parentGroupId);
        return new GroupSpec(name, parent);
    }
}
