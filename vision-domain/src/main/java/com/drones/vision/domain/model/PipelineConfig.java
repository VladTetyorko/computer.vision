package com.drones.vision.domain.model;

import java.util.Set;

/**
 * Per-stream configuration for a running pipeline.
 *
 * <p>{@code inferenceFps} governs frame sampling: the application layer
 * samples every Nth frame for inference while the full-FPS video passes
 * through untouched. {@code maxInFlightInferences} bounds concurrent calls
 * to {@code DetectionPort} so that a slow CV service can never stall the
 * video path — the application layer skips sampled frames rather than
 * queuing them once the bound is reached. {@code labelFilter} is
 * defensively copied to an immutable set; an empty set means "all labels".
 *
 * @param model                  model to run
 * @param confidenceThreshold    minimum confidence to keep a detection, range [0,1]
 * @param inferenceFps           target inference sample rate; must be positive
 * @param maxInFlightInferences  max concurrent in-flight {@code DetectionPort} calls; must be positive
 * @param overlayTelemetry       whether telemetry should be burned into the overlay
 * @param labelFilter            labels to keep; empty means all labels; defensively copied
 */
public record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps,
                              int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter) {

    public PipelineConfig {
        if (model == null) {
            throw new IllegalArgumentException("PipelineConfig model must not be null");
        }
        if (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "PipelineConfig confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (inferenceFps <= 0) {
            throw new IllegalArgumentException("PipelineConfig inferenceFps must be positive: " + inferenceFps);
        }
        if (maxInFlightInferences <= 0) {
            throw new IllegalArgumentException(
                    "PipelineConfig maxInFlightInferences must be positive: " + maxInFlightInferences);
        }
        if (labelFilter == null) {
            throw new IllegalArgumentException("PipelineConfig labelFilter must not be null");
        }
        labelFilter = Set.copyOf(labelFilter);
    }

    /**
     * Reasonable defaults for a new stream: the latest {@code "yolo"} model,
     * a 0.4 confidence threshold, 5 FPS inference sampling, at most 2
     * in-flight inference calls, telemetry overlay on, and no label
     * filtering (all labels kept).
     *
     * @return a default {@code PipelineConfig}
     */
    public static PipelineConfig defaults() {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 5, 2, true, Set.of());
    }
}
