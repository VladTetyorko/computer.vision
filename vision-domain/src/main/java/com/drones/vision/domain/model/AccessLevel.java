package com.drones.vision.domain.model;

/**
 * How much a subject (user or group) may do on a {@link MapLayer} (docs/plans/done/MAP-REWORK-PLAN.md §2.1).
 *
 * <p><strong>Declaration order is meaningful.</strong> Constants are declared least-to-most
 * permissive, so the natural enum ordinal ordering (comparable via {@link Enum#compareTo(Enum)})
 * is "more permissive" — {@code MapAccessPolicy} (vision-application) relies on this ordering to
 * take the max across every applicable grant rule and to answer "at least CONTRIBUTE" style
 * checks. Reordering these constants would silently change what "more access" means.
 */
public enum AccessLevel {

    /** May see the layer's marks/drawings; the least-permissive level. */
    VIEW,

    /** May additionally create/edit marks and drawings on the layer. */
    CONTRIBUTE,

    /** Full control: rename/delete the layer, edit any mark/drawing on it, manage grants — the
     *  most-permissive level. */
    MANAGE
}
