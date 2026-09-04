package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.UserId;

/**
 * One pilot assigned to an asset, on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2;
 * {@code role} added by docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B3) — the element type of
 * {@code GET /api/assets/{assetId}/pilots}.
 *
 * <p>{@code userId}: the org-settings roster UI resolves display names from the users list it
 * already holds. {@code role} is the CREW-CONTROL-PLAN.md IC-2 answer for this one assignment — the
 * seat this pilot holds on this asset ({@code PILOT} or {@code CREW}), the enum name.
 *
 * @param userId the assigned pilot's user id, as a canonical UUID string
 * @param role   the seat this assignment grants, the {@link AssignmentRole} enum name
 */
public record PilotResponse(String userId, String role) {

    /**
     * Maps a pilot user id and seat to its wire form.
     *
     * @param userId the assigned pilot's id
     * @param role   the seat this assignment grants
     * @return the response element
     */
    public static PilotResponse from(UserId userId, AssignmentRole role) {
        return new PilotResponse(userId.value().toString(), role.name());
    }
}
