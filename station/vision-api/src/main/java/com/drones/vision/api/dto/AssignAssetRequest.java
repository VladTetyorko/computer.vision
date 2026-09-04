package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.AssignmentRole;

import java.util.List;
import java.util.Locale;

/**
 * Request body for {@code PUT /api/assets/{assetId}/pilots/{userId}} (docs/plans/active/AUTH-ROLES-PLAN.md
 * §3.4, wave B3) — which seat this assignment grants. The body itself is optional; an absent body
 * (or an absent/{@code null} {@code role} field) means {@link AssignmentRole#PILOT} — the seat every
 * assignment granted before {@link AssignmentRole} existed, so an existing caller that never learns
 * about the new field keeps behaving exactly as before.
 *
 * @param role the seat name, matched case-insensitively against {@link AssignmentRole}; {@code null}
 *             (or an absent body entirely) means {@link AssignmentRole#PILOT}
 */
public record AssignAssetRequest(String role) {

    /**
     * Resolves {@link #role()} to an {@link AssignmentRole}, defaulting to {@link
     * AssignmentRole#PILOT}.
     *
     * @return the requested seat, or {@code PILOT} if none was given
     * @throws IllegalArgumentException if {@link #role()} is present but not a recognized
     *                                   {@link AssignmentRole} name (mapped to {@code 400})
     */
    public AssignmentRole toRole() {
        if (role == null || role.isBlank()) {
            return AssignmentRole.PILOT;
        }
        try {
            return AssignmentRole.valueOf(role.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unrecognized assignment role '" + role + "'; valid values: " + List.of(AssignmentRole.values()));
        }
    }
}
