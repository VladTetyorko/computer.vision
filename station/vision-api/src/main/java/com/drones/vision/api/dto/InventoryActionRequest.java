package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.MaintenanceKind;

import java.util.Locale;

/**
 * Request body for {@code POST /api/assets/{id}/inventory} (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * &sect;3.4) — the three inventory verbs {@code AssetCustodyService} exposes beyond custody:
 * ground it, release it from maintenance, or retire it for good.
 *
 * @param action  {@code GROUND}, {@code RELEASE}, or {@code RETIRE}, matched case-insensitively
 * @param kind    the kind of maintenance record to open; required for {@code GROUND}, ignored otherwise
 * @param summary a human-readable description; required for {@code GROUND}, ignored otherwise
 */
public record InventoryActionRequest(String action, String kind, String summary) {

    /** The three verbs {@link com.drones.vision.warehouse.application.custody.AssetCustodyService} exposes here. */
    public enum Action {
        GROUND, RELEASE, RETIRE
    }

    /**
     * Resolves {@link #action()} to one of the three supported verbs.
     *
     * @return the resolved action
     * @throws IllegalArgumentException if {@link #action()} is missing or not one of {@code GROUND}/
     *                                   {@code RELEASE}/{@code RETIRE}
     */
    public Action toAction() {
        if (action != null) {
            for (Action candidate : Action.values()) {
                if (candidate.name().equalsIgnoreCase(action)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException(
                "Unknown inventory action: " + action + " (valid values: GROUND, RELEASE, RETIRE)");
    }

    /**
     * Resolves {@link #kind()} for a {@code GROUND} action.
     *
     * @return the parsed maintenance kind
     * @throws IllegalArgumentException if {@link #kind()} is missing or not a known {@link MaintenanceKind}
     */
    public MaintenanceKind requireKind() {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank for action GROUND");
        }
        try {
            return MaintenanceKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown maintenance kind: " + kind, e);
        }
    }

    /**
     * Resolves {@link #summary()} for a {@code GROUND} action.
     *
     * @return the summary
     * @throws IllegalArgumentException if {@link #summary()} is blank
     */
    public String requireSummary() {
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary must not be blank for action GROUND");
        }
        return summary;
    }
}
