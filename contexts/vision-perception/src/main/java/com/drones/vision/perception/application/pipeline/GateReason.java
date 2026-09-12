package com.drones.vision.perception.application.pipeline;

/**
 * Why one sampler deadline did not result in a frame being sent to cv-service
 * (docs/plans/active/cv-orchestration/R2-backend-control-plane.md §2) — one value per branch of
 * {@code StreamPipeline#maybeDetect}. Paired with {@link GateDecision#outcome()}: present if and
 * only if the outcome is {@link GateOutcome#SKIPPED}.
 */
public enum GateReason {
    /** {@code PipelineConfig#detectionEnabled()} is false — the operator's own switch. */
    GATE_OFF,
    /** Enabled, but neither viewer demand nor {@code DetectionPolicy.ALWAYS}. */
    GATE_NO_DEMAND,
    /** This stream is served by the pull transport; the worker samples on its own schedule. */
    PULL_MODE,
    /** cv-service is in a known outage and this deadline is inside the backoff window. */
    OUTAGE_BACKOFF,
    /** The frame arrived between sample deadlines. */
    DEADLINE_NOT_DUE,
    /** {@code maxInFlightInferences} already in flight; the sample is skipped, never queued. */
    IN_FLIGHT_FULL,
    /** The detection port refused before doing any work (reconnect gate closed, or exhausted capacity). */
    CV_UNAVAILABLE
}
