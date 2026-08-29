package com.drones.vision.warehouse.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;
import java.time.Instant;

/**
 * One maintenance fact against an {@link Asset}: a grounding, an inspection coming due, a repair
 * in progress, or a plain note.
 *
 * <p>{@code closedAt == null} means the record is open; {@link #isOpen()} reads exactly that. An
 * asset may have several open records at once (a grounding and an unrelated repair note), and
 * releasing an asset ({@code AssetCustodyService#release}) closes only the open, flight-blocking
 * ones ({@link MaintenanceKind#blocksFlight()}) — a non-blocking {@link MaintenanceKind#NOTE} or
 * {@link MaintenanceKind#REPAIR} record stays open until someone explicitly closes it.
 *
 * @param id             typed maintenance-record identity
 * @param assetId        the asset this record is against
 * @param kind           what kind of fact this is
 * @param openedAt       when this record was opened
 * @param closedAt       when this record was closed, or {@code null} if still open; if present, must not be before {@code openedAt}
 * @param openedBy       the user who opened this record
 * @param summary        a human-readable description; must not be blank
 * @param flightSecondsAt cumulative flight seconds the asset had logged when this record was
 *                        opened, or {@code null} if not tracked; must not be negative
 */
public record MaintenanceRecord(MaintenanceId id, AssetId assetId, MaintenanceKind kind, Instant openedAt,
                                 Instant closedAt, UserId openedBy, String summary, Long flightSecondsAt) {

    public MaintenanceRecord {
        if (id == null) {
            throw new IllegalArgumentException("MaintenanceRecord id must not be null");
        }
        if (assetId == null) {
            throw new IllegalArgumentException("MaintenanceRecord assetId must not be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("MaintenanceRecord kind must not be null");
        }
        if (openedAt == null) {
            throw new IllegalArgumentException("MaintenanceRecord openedAt must not be null");
        }
        if (closedAt != null && closedAt.isBefore(openedAt)) {
            throw new IllegalArgumentException(
                    "MaintenanceRecord closedAt must not be before openedAt: " + closedAt + " < " + openedAt);
        }
        if (openedBy == null) {
            throw new IllegalArgumentException("MaintenanceRecord openedBy must not be null");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("MaintenanceRecord summary must not be blank");
        }
        if (flightSecondsAt != null && flightSecondsAt < 0) {
            throw new IllegalArgumentException(
                    "MaintenanceRecord flightSecondsAt must not be negative: " + flightSecondsAt);
        }
    }

    /**
     * Whether this record is still open.
     *
     * @return {@code true} iff {@link #closedAt()} is {@code null}
     */
    public boolean isOpen() {
        return closedAt == null;
    }

    /**
     * Returns a copy of this record closed at the given instant.
     *
     * @param closedAt when this record was closed; must not be {@code null} or before {@link
     *                 #openedAt()}
     * @return a new {@code MaintenanceRecord} with {@code closedAt} set
     */
    public MaintenanceRecord close(Instant closedAt) {
        if (closedAt == null) {
            throw new IllegalArgumentException("MaintenanceRecord close: closedAt must not be null");
        }
        return new MaintenanceRecord(id, assetId, kind, openedAt, closedAt, openedBy, summary, flightSecondsAt);
    }
}
