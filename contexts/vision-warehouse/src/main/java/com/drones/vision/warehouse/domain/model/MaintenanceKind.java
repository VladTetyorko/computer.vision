package com.drones.vision.warehouse.domain.model;

/**
 * What kind of maintenance fact a {@link MaintenanceRecord} carries.
 *
 * <p>{@link #GROUNDING} and {@link #INSPECTION_DUE} are blockers (docs/plans/active/
 * WAREHOUSE-UX-CONTEXT.md OQ1: readiness is NO-GO while one is open, and {@code engage} refuses);
 * {@link #REPAIR} and {@link #NOTE} are informational and never block flight on their own.
 */
public enum MaintenanceKind {

    /** Explicitly grounded by a manager — blocks flight until released. */
    GROUNDING,

    /** A scheduled inspection is due or overdue — blocks flight until released. */
    INSPECTION_DUE,

    /** Ongoing repair work being tracked; does not by itself block flight. */
    REPAIR,

    /** A free-form note with no operational consequence. */
    NOTE;

    /**
     * Whether an open record of this kind blocks flight.
     *
     * @return {@code true} for {@link #GROUNDING} and {@link #INSPECTION_DUE}
     */
    public boolean blocksFlight() {
        return this == GROUNDING || this == INSPECTION_DUE;
    }
}
