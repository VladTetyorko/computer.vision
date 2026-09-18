package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One pilot assigned to an asset, on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2;
 * {@code role} added by docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B3; {@code username}/{@code
 * displayName} by docs/plans/active/INVENTORY-REWORK-PLAN.md §6, wave W1) — the element type of
 * {@code GET /api/assets/{assetId}/pilots}.
 *
 * <p>{@code role} is the CREW-CONTROL-PLAN.md IC-2 answer for this one assignment — the seat this
 * pilot holds on this asset ({@code PILOT} or {@code CREW}), the enum name.
 *
 * <p><strong>The names travel with the row</strong> (D3). This used to carry {@code userId} alone,
 * on the assumption that "the org-settings roster UI resolves display names from the users list it
 * already holds" — true for an admin, false for everyone else: {@code GET /api/users} answers an
 * empty list for a pilot's {@code ASSIGNED_ASSETS} scope, so the drawer's Pilots section and the
 * asset page's pilots card rendered raw UUIDs (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3,
 * defect C). Both names are resolved server-side <em>by id</em> — a label lookup on a person already
 * named on this row, not a listing, so nothing about who a caller may enumerate changes.
 *
 * <p>Both are omitted rather than {@code null}-valued when the user record cannot be resolved (an
 * assignment outliving the account it names): a client shows the id it still has, and never a blank
 * where a person's name should be.
 *
 * @param userId      the assigned pilot's user id, as a canonical UUID string
 * @param role        the seat this assignment grants, the {@link AssignmentRole} enum name
 * @param username    the pilot's login name, or absent if the user record no longer resolves
 * @param displayName the pilot's human-readable name, or absent if the user record no longer resolves
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PilotResponse(String userId, String role, String username, String displayName) {

    /**
     * Maps a pilot user id, seat, and resolved user record to its wire form.
     *
     * @param userId the assigned pilot's id
     * @param role   the seat this assignment grants
     * @param user   the resolved user record, or {@code null} if the id no longer resolves to one
     * @return the response element
     */
    public static PilotResponse from(UserId userId, AssignmentRole role, User user) {
        return new PilotResponse(userId.value().toString(), role.name(),
                user == null ? null : user.username(),
                user == null ? null : user.displayName());
    }
}
