package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;

/**
 * One asset a pilot is assigned to, on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2;
 * {@code role} added by docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B3) — the element type of
 * {@code GET /api/me/assignments}.
 *
 * <p>{@code assetId}: the pilot's Fly picker / fleet list already fetches the full asset summaries
 * it needs through the scoped {@code GET /api/assets}, which for a pilot returns exactly their
 * assigned assets. {@code role} is the CREW-CONTROL-PLAN.md IC-2 answer — the seat the caller holds
 * on this one asset, the {@link AssignmentRole} enum name.
 *
 * @param assetId the assigned asset's id, as a canonical UUID string
 * @param role    the seat this assignment grants, the {@link AssignmentRole} enum name
 */
public record AssignmentResponse(String assetId, String role) {

    /**
     * Maps an asset id and seat to its wire form.
     *
     * @param assetId the assigned asset id
     * @param role    the seat this assignment grants
     * @return the response element
     */
    public static AssignmentResponse from(AssetId assetId, AssignmentRole role) {
        return new AssignmentResponse(assetId.value().toString(), role.name());
    }
}
