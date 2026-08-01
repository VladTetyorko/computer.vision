package com.drones.vision.domain.model;

/**
 * The lifecycle state of a {@link Mark} (docs/TACTICAL-MARKS-PLAN.md §1).
 */
public enum MarkStatus {

    /** The mark is current and should render on the shared operational picture. */
    ACTIVE,

    /** The mark has been resolved/dismissed; retained for history, not for display. */
    CLEARED
}
