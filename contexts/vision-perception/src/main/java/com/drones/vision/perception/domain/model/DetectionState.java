package com.drones.vision.perception.domain.model;

/**
 * Which of the two independent detection gates (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.6) is
 * responsible for the current absence (or presence) of boxes on a stream, made legible instead of
 * left for a client to infer from {@code submittedFps == 0} &mdash; a reading that cannot tell
 * "the operator turned it off," "nobody is watching right now," and "the detector is stalled" apart,
 * even though only the first two are this enum's business.
 *
 * <p><b>This enum reports gating, never health.</b> {@link #RUNNING} means both gates are open and
 * says nothing about whether {@code DetectionPort} is actually answering &mdash; a stalled or
 * unreachable cv-service still reads {@code RUNNING} throughout its outage; that is what the
 * pre-existing outage/backoff machinery and its {@code PIPELINE_ERROR} events are for, not this
 * enum. Wiring this to a status light as if it meant "healthy" is the exact misuse this javadoc
 * exists to head off.
 *
 * @see StreamPipeline#detectionDemand()
 * @see PipelineConfig#detectionEnabled()
 */
public enum DetectionState {
    /** {@link PipelineConfig#detectionEnabled()} is {@code false} &mdash; the operator's own choice. */
    OFF,
    /**
     * Enabled, but {@link StreamPipeline#detectionDemand()} is currently {@code false} &mdash; past
     * grace, nobody is consuming this stream's detections. Not a fault.
     */
    IDLE_NO_VIEWERS,
    /** Both gates are open. Says nothing about detector health &mdash; see this enum's own javadoc. */
    RUNNING
}
