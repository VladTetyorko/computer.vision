package com.drones.vision.perception.application.pipeline;

/**
 * The demand facts this module owns at the moment one gate decision was made
 * (docs/plans/active/cv-orchestration/R2-backend-control-plane.md §2).
 *
 * <p><strong>Known limitation:</strong> the plan's {@code sse | pose | poll} breakdown of viewer
 * demand is not obtainable in this module. Those three OR-terms are private to
 * {@code station/vision-api}'s {@code LiveAndPollDetectionDemand} and reach this module as a
 * single boolean through {@code DetectionDemandPort#detectionWanted} — naming the term would
 * require widening that port's return type. What is recorded here is every term this module
 * actually owns.
 *
 * @param detectionEnabled the operator's own switch, {@code PipelineConfig#detectionEnabled()}
 * @param viewerDemand     the single boolean {@code DetectionDemandPort#detectionWanted} reports
 * @param policyAlwaysOn   whether this asset has opted into {@code DetectionPolicy.ALWAYS}
 */
public record DemandSnapshot(boolean detectionEnabled, boolean viewerDemand, boolean policyAlwaysOn) {
}
