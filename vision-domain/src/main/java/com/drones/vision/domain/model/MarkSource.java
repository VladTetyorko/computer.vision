package com.drones.vision.domain.model;

/**
 * How a {@link Mark} came to exist (docs/plans/done/TACTICAL-MARKS-PLAN.md §1).
 */
public enum MarkSource {

    /** Dropped directly by a user clicking on a shared map. */
    MANUAL,

    /** Created by projecting a drone's pose ({@link GeoProjection#project}) — an estimate, not a
     *  precise fix; draggable/editable so the operator can correct it. */
    DETECTION
}
