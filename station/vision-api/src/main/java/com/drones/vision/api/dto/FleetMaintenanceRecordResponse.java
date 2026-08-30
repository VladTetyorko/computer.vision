package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.maintenance.MaintenanceRecordSummary;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/maintenance} — {@link MaintenanceRecordResponse}'s
 * fleet-wide counterpart, carrying the asset's name and category slug alongside each record so the
 * Maintenance page needs one call, not one {@code GET /api/assets/{id}/maintenance} per grounded
 * asset (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5; docs/plans/active/
 * WAREHOUSE-UX-CONTEXT.md W7 handoff).
 *
 * <p>{@code closedAt} and {@code flightSecondsAt} are omitted from the JSON entirely (rather than
 * serialized as {@code null}), same convention as {@link MaintenanceRecordResponse}.
 *
 * @param id              record identity, as a canonical UUID string
 * @param assetId         the asset this record is against, as a canonical UUID string
 * @param assetName       the asset's display name
 * @param categoryId      the asset's category slug
 * @param kind            {@code GROUNDING}, {@code INSPECTION_DUE}, {@code REPAIR}, or {@code NOTE}
 * @param openedAt        when the record was opened
 * @param closedAt        when the record was closed, or absent if still open
 * @param openedBy        who opened it, as a canonical UUID string
 * @param summary         a human-readable description
 * @param flightSecondsAt the asset's cumulative flight seconds when this record was opened, or
 *                        absent if not known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FleetMaintenanceRecordResponse(String id, String assetId, String assetName, String categoryId,
                                               String kind, Instant openedAt, Instant closedAt, String openedBy,
                                               String summary, Long flightSecondsAt) {

    /**
     * Maps a {@link MaintenanceRecordSummary} read model to its wire representation.
     *
     * @param recordSummary the summary to map
     * @return the response body element for {@code recordSummary}
     */
    public static FleetMaintenanceRecordResponse from(MaintenanceRecordSummary recordSummary) {
        MaintenanceRecord record = recordSummary.record();
        return new FleetMaintenanceRecordResponse(record.id().value().toString(),
                record.assetId().value().toString(), recordSummary.assetName(), recordSummary.categoryId().slug(),
                record.kind().name(), record.openedAt(), record.closedAt(), record.openedBy().value().toString(),
                record.summary(), record.flightSecondsAt());
    }
}
