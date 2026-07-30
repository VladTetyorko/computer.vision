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
 * <p>{@code overlayBurnIn} (docs/MVP2-PLAN.md §V, V-e) gates whether the
 * application layer renders detection/telemetry overlays onto the published
 * video at all. {@code true} (the default, current behavior) burns boxes/OSD
 * into every published frame — the only thing an HLS-fallback viewer, an
 * external player, or a future recording ever sees, since none of them run
 * the SPA's own vector overlay. {@code false} skips rendering entirely: the
 * app's own SPA viewers already draw the identical boxes as a client-side
 * vector overlay (redundant burned pixels for them), so a stream whose only
 * consumers are app viewers can trade that redundancy away for a cheaper
 * encode path — see {@code StreamPipeline}'s javadoc for exactly what the
 * skip saves.
 *
 * <p>{@code detectionEnabled} (docs/CV-CONTROL-PLAN.md §1, Wave B) is the
 * per-stream detection on/off switch: {@code false} means the pipeline skips
 * {@code detect()} entirely — no {@code DetectionPort} calls are made, so
 * detection costs zero CPU — while video keeps flowing at full rate,
 * untouched. Re-enabling resumes detection on the next sampled frame. The
 * skip itself is enforced by the application layer's {@code StreamPipeline}
 * (Wave C); this record only carries the flag.
 *
 * @param model                  model to run
 * @param confidenceThreshold    minimum confidence to keep a detection, range [0,1]
 * @param inferenceFps           target inference sample rate; must be positive
 * @param maxInFlightInferences  max concurrent in-flight {@code DetectionPort} calls; must be positive
 * @param overlayTelemetry       whether telemetry should be burned into the overlay
 * @param labelFilter            labels to keep; empty means all labels; defensively copied
 * @param eventRule              debounce settings for {@link DetectionEvent} tracking
 * @param overlayBurnIn          whether to render overlays onto published video at all
 * @param detectionEnabled       whether the pipeline runs detection at all; {@code false} skips
 *                               {@code detect()} entirely while video keeps flowing
 */
public record PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps,
                              int maxInFlightInferences, boolean overlayTelemetry, Set<String> labelFilter,
                              EventRuleConfig eventRule, boolean overlayBurnIn, boolean detectionEnabled) {

    /** Default for {@link #overlayBurnIn()} on every N-1-arg convenience constructor — unchanged behavior. */
    public static final boolean DEFAULT_OVERLAY_BURN_IN = true;

    /** Default for {@link #detectionEnabled()} on every N-1-arg convenience constructor — unchanged behavior. */
    public static final boolean DEFAULT_DETECTION_ENABLED = true;

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
     * Convenience constructor for callers that don't care about {@link #detectionEnabled()} —
     * defaults it to {@link #DEFAULT_DETECTION_ENABLED} (unchanged behavior), the same "N-1-arg
     * convenience ctor" idiom used elsewhere ({@code Asset}'s 6-arg ctor, {@code AssetUsage}'s
     * 7-arg ctor, this record's own 6-/7-arg ctors below). This was the canonical constructor
     * before docs/CV-CONTROL-PLAN.md Wave B added {@link #detectionEnabled()}; every pre-existing
     * 8-arg call site compiles unchanged.
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           boolean overlayTelemetry, Set<String> labelFilter, EventRuleConfig eventRule,
                           boolean overlayBurnIn) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, overlayTelemetry, labelFilter,
                eventRule, overlayBurnIn, DEFAULT_DETECTION_ENABLED);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #overlayBurnIn()} — defaults
     * it to {@link #DEFAULT_OVERLAY_BURN_IN} (unchanged behavior), chaining onto the 8-arg
     * convenience ctor above (so {@link #detectionEnabled()} also defaults to
     * {@link #DEFAULT_DETECTION_ENABLED}).
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           boolean overlayTelemetry, Set<String> labelFilter, EventRuleConfig eventRule) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, overlayTelemetry, labelFilter,
                eventRule, DEFAULT_OVERLAY_BURN_IN);
    }

    /**
     * Convenience constructor for callers that don't care about {@link #eventRule()} either —
     * defaults it to {@link EventRuleConfig#defaults()}, chaining onto the 7-arg convenience ctor
     * above (so {@link #overlayBurnIn()} also defaults to {@link #DEFAULT_OVERLAY_BURN_IN}).
     */
    public PipelineConfig(ModelRef model, double confidenceThreshold, int inferenceFps, int maxInFlightInferences,
                           boolean overlayTelemetry, Set<String> labelFilter) {
        this(model, confidenceThreshold, inferenceFps, maxInFlightInferences, overlayTelemetry, labelFilter,
                EventRuleConfig.defaults());
    }

    /**
     * Reasonable defaults for a new stream: the {@code "yolo26n.pt"} model
     * (docs/CV-CONTROL-PLAN.md §1, Wave B — the real checkpoint id cv-service
     * already falls back to; the previous {@code "yolo"} id matched no actual
     * checkpoint and relied on that silent fallback), a 0.4 confidence
     * threshold, 10 FPS inference sampling, at most 2 in-flight inference
     * calls, telemetry overlay on, no label filtering (all labels kept),
     * {@link EventRuleConfig#defaults()}, overlay burn-in on, and detection
     * enabled.
     *
     * @return a default {@code PipelineConfig}
     */
    public static PipelineConfig defaults() {
        return new PipelineConfig(new ModelRef("yolo26n.pt", "latest"), 0.4, 10, 2, true, Set.of(),
                EventRuleConfig.defaults(), DEFAULT_OVERLAY_BURN_IN, DEFAULT_DETECTION_ENABLED);
    }
}
