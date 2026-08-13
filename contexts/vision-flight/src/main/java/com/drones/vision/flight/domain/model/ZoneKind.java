package com.drones.vision.flight.domain.model;

/**
 * What a {@link GeofenceZone}'s polygon means for breach evaluation (docs/plans/done/OPS-CORE-PLAN.md §G).
 *
 * <p>The polygon itself never changes shape between the two kinds — only how {@code
 * GeofenceMonitor} (vision-application) reads it: a {@link #KEEP_OUT} zone is forbidden airspace
 * (being inside it is the breach), while a {@link #KEEP_IN} zone is the opposite — the aircraft is
 * expected to stay inside it, so being outside it is the breach.
 */
public enum ZoneKind {

    /** The aircraft must stay inside this zone's polygon; outside it is a breach. */
    KEEP_IN,

    /** The aircraft must stay outside this zone's polygon; inside it is a breach. */
    KEEP_OUT
}
