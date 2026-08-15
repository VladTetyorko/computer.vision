package com.drones.vision.api.dto;

import com.drones.vision.map.domain.model.LayerGrant;

import java.util.List;

/**
 * Body of {@code PUT /api/map/layers/{id}/grants} (docs/plans/done/MAP-REWORK-PLAN.md §4.1) — a
 * <strong>wholesale replacement</strong> of the layer's access list, not a delta: whatever is sent
 * becomes the complete grant set, and an absent/empty list clears every grant.
 *
 * @param grants the complete replacement list; {@code null} is treated as empty
 */
public record SetGrantsRequest(List<GrantDto> grants) {

    /**
     * @return the replacement list as domain grants
     * @throws IllegalArgumentException if any entry has an unrecognized {@code subjectType}/{@code
     *                                   level} or a malformed {@code subjectId} (→ 400)
     */
    public List<LayerGrant> toGrants() {
        return grants == null ? List.of() : grants.stream().map(GrantDto::toGrant).toList();
    }
}
