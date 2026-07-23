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
 * <p>{@code eventRule} (docs/MVP2-PLAN.md §E, E-a) governs debounced {@link
 * DetectionEvent} tracking — the rule engine consuming this pipeline's
 * results decides, independently of {@code labelFilter}, when a label's
 * streak of qualifying results opens/closes an event; see {@link
 * EventRuleConfig}.
 *
 * @param model                  model to run
 * @param confidenceThreshold    minimum confidence to keep a detection, range [0,1]
 * @param inferenceFps           target inference sample rate; must be positive
 * @param maxInFlightInferences  max concurrent in-flight {@code DetectionPort} calls; must be positive
 * @param overlayTelemetry       whether telemetry should be burned into the overlay
 * @param labelFilter            labels to keep; empty means all labels; defensively copied
 * @param eventRule              debounce settings for {@link DetectionEvent} tracking
 */
public record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps,
                              int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter,
                              EventRuleConfig eventRule) {

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
        if (eventRule == null) {
            throw new IllegalArgumentException("PipelineConfig eventRule must not be null");
        }
        labelFilter = Set.copyOf(labelFilter);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #eventRule()} — defaults it
     * to {@link EventRuleConfig#defaults()}, the same "N-1-arg convenience ctor" idiom used
     * elsewhere ({@code Asset}'s 6-arg ctor, {@code AssetUsage}'s 7-arg ctor).
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           boolean overlayTelemetry, Set<String> labelFilter) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, overlayTelemetry, labelFilter,
                EventRuleConfig.defaults());
    }

    /**
     * Reasonable defaults for a new stream: the latest {@code "yolo"} model,
     * a 0.4 confidence threshold, 10 FPS inference sampling, at most 2
     * in-flight inference calls, telemetry overlay on, no label
     * filtering (all labels kept), and {@link EventRuleConfig#defaults()}.
     *
     * @return a default {@code PipelineConfig}
     */
    public static PipelineConfig defaults() {
        return new PipelineConfig(new ModelRef("yolo", "latest"), 0.4, 10, 2, true, Set.of(),
                EventRuleConfig.defaults());
    }
}
