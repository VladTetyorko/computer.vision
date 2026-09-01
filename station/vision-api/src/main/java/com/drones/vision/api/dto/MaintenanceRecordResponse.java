package com.drones.vision.api.dto;

import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Response body element for {@code GET /api/assets/{id}/maintenance} and the write endpoints that
 * open/close a record (docs/plans/active/WAREHOUSE-UX-PLAN.md wave W7).
 *
 * <p>{@code closedAt} and {@code flightSecondsAt} are omitted from the JSON entirely (rather than
 * serialized as {@code null}) for an open record / one opened without a known flight-hours
 * reading.
 *
 * @param id              record identity, as a canonical UUID string
 * @param assetId         the asset this record is against, as a canonical UUID string
 * @param kind            {@code GROUNDING}, {@code INSPECTION_DUE}, {@code REPAIR}, or {@code NOTE}
 * @param openedAt        when the record was opened
 * @param closedAt        when the record was closed, or absent if still open
 * @param openedBy        who opened it, as a canonical UUID string
 * @param summary         a human-readable description
 * @param flightSecondsAt the asset's cumulative flight seconds when this record was opened, or
 *                        absent if not known
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MaintenanceRecordResponse(String id, String assetId, String kind, Instant openedAt, Instant closedAt,
                                         String openedBy, String summary, Long flightSecondsAt) {

    /**
     * Maps a domain {@link MaintenanceRecord} to its wire representation.
     *
     * @param record the record to map
     * @return the response body element for {@code record}
     */
    public static MaintenanceRecordResponse from(MaintenanceRecord record) {
        return new MaintenanceRecordResponse(record.id().value().toString(), record.assetId().value().toString(),
                record.kind().name(), record.openedAt(), record.closedAt(), record.openedBy().value().toString(),
                record.summary(), record.flightSecondsAt());
    }
}
