package com.drones.vision.learning.domain.model;

/**
 * Provenance of one {@link Annotation} — how its box/label came to be, not a behavior. Recorded so
 * the labeling loop can later tell where the operator agreed with the model versus where they had
 * to correct it (docs/plans/done/CV-TRAINING-PLAN.md §E) — that correction is the improvement signal.
 */
public enum AnnotationSource {

    /** Pre-filled from a live {@code Detection} at capture time; not yet confirmed by a human. */
    MODEL,

    /** Drawn, dragged, or edited by the operator during labeling. */
    OPERATOR
}
