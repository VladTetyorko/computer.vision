package com.drones.vision.map.domain.model;

/**
 * Friend/enemy affiliation of a map object (docs/plans/done/MAP-REWORK-PLAN.md §2.1) — APP-6-inspired, the
 * "whose it is" dimension of a {@link Mark}, orthogonal to {@link MarkKind}'s "what it is".
 *
 * <p>Symbology (frame shape/colour per affiliation) is a web-layer concern, not modeled here.
 */
public enum Affiliation {

    /** Own or allied — renders with friendly symbology. Own assets always render as this. */
    FRIENDLY,

    /** A known adversary. */
    HOSTILE,

    /** Known to be neither friendly nor hostile. */
    NEUTRAL,

    /** Affiliation has not been established. */
    UNKNOWN
}
