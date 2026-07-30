package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Group;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An org-chart group on the wire (docs/U-SCOPE-PLAN.md, U-e slice 2) — the element type of {@code
 * GET /api/groups} and the body of {@code POST /api/groups}.
 *
 * @param id            the group id, as a canonical UUID string
 * @param name          human-readable name
 * @param parentGroupId the enclosing group's id, or absent for a root group
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupResponse(String id, String name, String parentGroupId) {

    /**
     * Maps a domain {@link Group} to its wire representation.
     *
     * @param group the group to map
     * @return the response body for {@code group}
     */
    public static GroupResponse from(Group group) {
        String parent = group.parentGroupId() == null ? null : group.parentGroupId().value().toString();
        return new GroupResponse(group.id().value().toString(), group.name(), parent);
    }
}
