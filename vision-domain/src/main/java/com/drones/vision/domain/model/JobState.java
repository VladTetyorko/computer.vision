package com.drones.vision.domain.model;

/**
 * The state of one {@link TrainingProgress} message in a training job's stream
 * (docs/plans/done/CV-TRAINING-PLAN.md §6/§7, Phase 2) — mirrors {@code cv.proto}'s {@code JobState} enum,
 * minus {@code JOB_STATE_UNSPECIFIED}: mapping that proto zero-value to a concrete state (or
 * rejecting it) is an adapter-boundary concern, not this enum's. Pure marker, no behavior, no
 * dedicated test (same convention as {@link Capability}/{@link EventType}/{@link PixelFormat}).
 */
public enum JobState {
    RUNNING,
    SUCCEEDED,
    FAILED
}
