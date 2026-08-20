package com.drones.vision.perception.domain.model;

/**
 * The state of one {@link IngestProgress} message in a region-ingest job's stream — mirrors {@code
 * cv.proto}'s {@code JobState} enum, minus {@code JOB_STATE_UNSPECIFIED} (an adapter-boundary
 * concern, not this enum's). A perception-local mirror, not a shared kernel/platform type: this
 * context has no dependency on {@code vision-learning}, which owns the only other Java copy of the
 * same proto enum. Pure marker, no behavior, no dedicated test (same convention as {@code
 * Capability}/{@code EventType}/{@code PixelFormat}).
 */
public enum IngestState {
    RUNNING,
    SUCCEEDED,
    FAILED
}
