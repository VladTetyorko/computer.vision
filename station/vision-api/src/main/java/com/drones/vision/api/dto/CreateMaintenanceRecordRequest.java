package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.MaintenanceKind;

import java.util.Locale;

/**
 * Request body for {@code POST /api/assets/{id}/maintenance} — opens a maintenance record directly
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md wave W7), without also grounding the asset (see {@code
 * MaintenanceService#open}'s own javadoc; use {@code POST /api/assets/{id}/inventory} with action
 * {@code GROUND} instead when the record should also ground it).
 *
 * @param kind    {@code GROUNDING}, {@code INSPECTION_DUE}, {@code REPAIR}, or {@code NOTE}, matched
 *                case-insensitively
 * @param summary a human-readable description; must not be blank
 */
public record CreateMaintenanceRecordRequest(String kind, String summary) {

    /**
     * Resolves {@link #kind()} to a known {@link MaintenanceKind}.
     *
     * @return the parsed kind
     * @throws IllegalArgumentException if {@link #kind()} is missing or not a known {@link MaintenanceKind}
     */
    public MaintenanceKind toKind() {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("kind must not be blank");
        }
        try {
            return MaintenanceKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown maintenance kind: " + kind, e);
        }
    }
}
