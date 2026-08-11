package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.domain.model.AccessLevel;
import com.drones.vision.domain.model.LayerGrant;

import java.util.UUID;

/**
 * One entry of a layer's access list (docs/plans/done/MAP-REWORK-PLAN.md §4.2), both directions: read back on
 * {@link LayerResponse#grants()} (only when the caller manages the layer) and sent in wholesale on
 * {@code PUT /api/map/layers/{id}/grants}.
 *
 * @param subjectType {@code "USER"} or {@code "GROUP"}
 * @param subjectId   the user or group id, as a canonical UUID string
 * @param level       {@code "VIEW"}, {@code "CONTRIBUTE"} or {@code "MANAGE"}
 */
public record GrantDto(String subjectType, String subjectId, String level) {

    /**
     * @param grant the domain grant to map
     * @return its wire form
     */
    public static GrantDto from(LayerGrant grant) {
        return new GrantDto(grant.subjectType().name(), grant.subjectId().toString(), grant.level().name());
    }

    /**
     * @return this entry as a domain {@link LayerGrant}
     * @throws IllegalArgumentException if {@code subjectType}/{@code level} is unrecognized, or
     *                                   {@code subjectId} is not a UUID (→ 400)
     */
    public LayerGrant toGrant() {
        return new LayerGrant(
                EnumParsing.require(LayerGrant.SubjectType.class, "subjectType", subjectType),
                requireSubjectId(subjectId),
                EnumParsing.require(AccessLevel.class, "level", level));
    }

    private static UUID requireSubjectId(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("GrantDto subjectId must not be blank");
        }
        try {
            return UUID.fromString(subjectId.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GrantDto subjectId must be a valid UUID: " + subjectId, e);
        }
    }
}
