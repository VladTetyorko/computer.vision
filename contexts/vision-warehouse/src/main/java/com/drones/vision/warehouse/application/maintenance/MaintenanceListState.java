package com.drones.vision.warehouse.application.maintenance;

/**
 * The {@code state} filter {@link MaintenanceService#fleetWide} accepts — the query-parameter
 * vocabulary of {@code GET /api/maintenance} (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5).
 */
public enum MaintenanceListState {

    /**
     * Every currently-open record fleet-wide ({@link
     * com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort#findOpen()}).
     */
    OPEN,

    /**
     * The most recently closed records fleet-wide, bounded by the caller's {@code limit} ({@link
     * com.drones.vision.warehouse.domain.port.MaintenanceRepositoryPort#findRecentlyClosed(int)}).
     */
    CLOSED,

    /** {@link #OPEN} plus {@link #CLOSED} — every open record, plus the most recently closed ones. */
    ALL
}
