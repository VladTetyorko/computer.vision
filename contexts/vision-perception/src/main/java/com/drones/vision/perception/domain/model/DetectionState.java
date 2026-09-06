package com.drones.vision.perception.domain.model;

/**
 * Which of {@code StreamPipeline}'s detection gates (docs/plans/done/CV-DEMAND-PLAN.md &sect;3.6,
 * widened by docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave D2 from two gates to three questions) is
 * responsible for the current absence (or presence) of boxes on a stream, made legible instead of
 * left for a client to infer from {@code submittedFps == 0} &mdash; a reading that cannot tell
 * "the operator turned it off," "nobody is watching right now," and "the detector is stalled" apart,
 * even though only the first two are this enum's business.
 *
 * <p><b>This enum reports gating, never health.</b> {@link #RUNNING}/{@link #RUNNING_UNWATCHED} say
 * nothing about whether {@code DetectionPort} is actually answering &mdash; a stalled or
 * unreachable cv-service still reads one of them throughout its outage; that is what the
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
     * Enabled, but neither {@link StreamPipeline#detectionDemand()} nor this asset's {@code
     * DetectionPolicy.ALWAYS} opt-in holds &mdash; past grace, nobody is consuming this stream's
     * detections and nothing else is asking for inference either. Not a fault.
     */
    IDLE_NO_VIEWERS,
    /**
     * Enabled and inferring &mdash; a {@code DetectionPolicy.ALWAYS} asset (docs/plans/active/
     * ALWAYS-ON-FLOW-PLAN.md wave D1) keeping detection running with no current viewer. Durable
     * persistence/events proceed exactly as {@link #RUNNING}; only the live read models a viewer
     * would see are not being updated, because there is no viewer to show them to. Not a fault, and
     * unreachable for an asset that has never opted into {@code ALWAYS} (the default {@code
     * ON_VIEW}) &mdash; such an asset's inference and live gates always agree, so this state and
     * {@link #IDLE_NO_VIEWERS} collapse to one outcome exactly as before this state existed.
     */
    RUNNING_UNWATCHED,
    /** Both the inference and live gates are open. Says nothing about detector health &mdash; see this enum's own javadoc. */
    RUNNING
}
