package com.drones.vision.domain.model;

/**
 * The geometry shape a {@link Drawing} represents (docs/MAP-REWORK-PLAN.md §2.1). Drives that
 * record's own point-count invariant — see {@link Drawing}'s javadoc.
 */
public enum DrawKind {

    /** An open polyline; at least 2 points. */
    LINE,

    /** A closed area; at least 3 points. */
    POLYGON,

    /** A polyline rendered with an arrowhead at its last point; at least 2 points. */
    ARROW,

    /** A single labeled point annotation; exactly 1 point, label required. */
    TEXT
}
