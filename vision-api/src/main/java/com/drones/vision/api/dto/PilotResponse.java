package com.drones.vision.api.dto;

import com.drones.vision.domain.model.UserId;

/**
 * One pilot assigned to an asset, on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2) — the
 * element type of {@code GET /api/assets/{assetId}/pilots}.
 *
 * <p>Just the id: the org-settings roster UI resolves display names from the users list it already
 * holds. A record so the shape can grow (a display name, an assigned-at timestamp) without a
 * breaking wire change.
 *
 * @param userId the assigned pilot's user id, as a canonical UUID string
 */
public record PilotResponse(String userId) {

    /**
     * Maps a pilot user id to its wire form.
     *
     * @param userId the assigned pilot's id
     * @return the response element
     */
    public static PilotResponse from(UserId userId) {
        return new PilotResponse(userId.value().toString());
    }
}
