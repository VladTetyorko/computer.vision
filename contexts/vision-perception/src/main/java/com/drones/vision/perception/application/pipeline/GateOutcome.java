package com.drones.vision.perception.application.pipeline;

/**
 * What one sampler deadline actually did (docs/plans/active/cv-orchestration/
 * R2-backend-control-plane.md §2).
 */
public enum GateOutcome {
    /** The frame was sent to cv-service as a normal sample. */
    SENT,
    /** The frame was sent to cv-service as an outage-recovery probe, ignoring the sample deadline. */
    PROBE,
    /** No frame was sent; {@link GateDecision#reason()} says why. */
    SKIPPED
}
