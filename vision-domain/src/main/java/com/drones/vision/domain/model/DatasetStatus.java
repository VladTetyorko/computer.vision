package com.drones.vision.domain.model;

/**
 * Lifecycle state of a {@link Dataset} (docs/plans/done/CV-TRAINING-PLAN.md §1).
 */
public enum DatasetStatus {

    /** Accepting new captures/labels; the normal working state. */
    OPEN,

    /** Retired — no longer accepting new captures; retained for history. */
    ARCHIVED
}
