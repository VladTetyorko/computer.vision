package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;

/**
 * One asset a pilot is assigned to, on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2) —
 * the element type of {@code GET /api/me/assignments}.
 *
 * <p>Deliberately just the id: the pilot's Fly picker / fleet list already fetches the full asset
 * summaries it needs through the scoped {@code GET /api/assets}, which for a pilot returns exactly
 * their assigned assets. A record (rather than a bare string array) so the shape can grow (a
 * display name, an assigned-at timestamp) without a breaking wire change.
 *
 * @param assetId the assigned asset's id, as a canonical UUID string
 */
public record AssignmentResponse(String assetId) {

    /**
     * Maps an asset id to its wire form.
     *
     * @param assetId the assigned asset id
     * @return the response element
     */
    public static AssignmentResponse from(AssetId assetId) {
        return new AssignmentResponse(assetId.value().toString());
    }
}
