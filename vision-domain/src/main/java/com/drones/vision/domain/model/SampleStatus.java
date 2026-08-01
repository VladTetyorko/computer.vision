package com.drones.vision.domain.model;

/**
 * Review state of a {@link TrainingSample} (docs/CV-TRAINING-PLAN.md §1).
 */
public enum SampleStatus {

    /** Captured; its annotations are still the model's raw suggestions, not yet reviewed. */
    PENDING,

    /** Operator confirmed/corrected the annotations — the only status a dataset export includes. */
    LABELED,

    /** Operator rejected the frame; kept for provenance, never exported. */
    DISCARDED
}
