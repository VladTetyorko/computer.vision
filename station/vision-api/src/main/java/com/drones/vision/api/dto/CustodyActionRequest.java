package com.drones.vision.api.dto;

import com.drones.vision.kernel.UserId;

/**
 * Request body for {@code POST /api/assets/{id}/custody} (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * &sect;3.4) — {@code ISSUE} hands an in-stock asset to a custodian, {@code RETURN} brings an
 * issued asset back to stock.
 *
 * @param action      {@code ISSUE} or {@code RETURN}, matched case-insensitively
 * @param custodianId the receiving user, as a canonical UUID string; required for {@code ISSUE},
 *                     ignored for {@code RETURN}
 * @param location    a free-form note of where the asset is going; optional, {@code ISSUE} only
 */
public record CustodyActionRequest(String action, String custodianId, String location) {

    /** The two verbs {@link com.drones.vision.warehouse.application.custody.AssetCustodyService} exposes here. */
    public enum Action {
        ISSUE, RETURN
    }

    /**
     * Resolves {@link #action()} to one of the two supported verbs.
     *
     * @return the resolved action
     * @throws IllegalArgumentException if {@link #action()} is missing or not one of {@code ISSUE}/{@code RETURN}
     */
    public Action toAction() {
        if (action != null) {
            for (Action candidate : Action.values()) {
                if (candidate.name().equalsIgnoreCase(action)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown custody action: " + action + " (valid values: ISSUE, RETURN)");
    }

    /**
     * Resolves {@link #custodianId()} for an {@code ISSUE} action.
     *
     * @return the parsed custodian id
     * @throws IllegalArgumentException if {@link #custodianId()} is missing or not a valid UUID
     */
    public UserId requireCustodianId() {
        if (custodianId == null || custodianId.isBlank()) {
            throw new IllegalArgumentException("custodianId must not be blank for action ISSUE");
        }
        return UserId.of(custodianId);
    }
}
