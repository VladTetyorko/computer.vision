package com.drones.vision.api.dto;

import com.drones.vision.domain.model.ModelRef;
import com.drones.vision.domain.model.PipelineConfig;

import java.util.List;
import java.util.Set;

/**
 * Optional request body for {@code POST /api/devices/{deviceId}/stream}.
 *
 * <p>All fields are optional; each present field overrides the
 * corresponding value from {@link PipelineConfig#defaults()}. All other
 * settings (max in-flight inferences, telemetry overlay) come from the
 * defaults untouched — Phase 1 only exposed the two settings a dev-console
 * user is likely to want to tweak (KISS); {@code overlayBurnIn}
 * (docs/MVP2-PLAN.md §V, V-e), {@code model} (the frontend's detection-model
 * picker), and {@code labelFilter}/{@code detectionEnabled}
 * (docs/CV-CONTROL-PLAN.md §2) were added the same way, per-stream,
 * mirroring this pattern rather than a global toggle.
 *
 * @param confidenceThreshold overrides {@link PipelineConfig#confidenceThreshold()} if present
 * @param inferenceFps        overrides {@link PipelineConfig#inferenceFps()} if present
 * @param overlayBurnIn       overrides {@link PipelineConfig#overlayBurnIn()} if present
 * @param model               overrides {@link PipelineConfig#model()}'s {@code id} if present/non-blank —
 *                             the raw string verbatim, which may be a comma-composite (e.g. {@code
 *                             "yolo11n.pt,orion12l.pt"}) that {@code cv-service}'s model registry parses
 *                             server-side; this DTO never splits it. The resulting {@code ModelRef}'s
 *                             {@code version} is always {@link PipelineConfig#defaults()}'s own (there is
 *                             no per-stream version override, only a model-id one)
 * @param labelFilter         overrides {@link PipelineConfig#labelFilter()} if present (a JSON array); an
 *                             absent field keeps the default (empty, "all labels"); an explicit empty array
 *                             is itself a real value with the same "all labels" meaning
 * @param detectionEnabled    overrides {@link PipelineConfig#detectionEnabled()} if present; absent keeps
 *                             the default ({@code true})
 */
public record StartStreamRequest(Double confidenceThreshold, Integer inferenceFps, Boolean overlayBurnIn,
                                  String model, List<String> labelFilter, Boolean detectionEnabled) {

    /** No overrides: use every default from {@link PipelineConfig#defaults()}. */
    public static final StartStreamRequest EMPTY = new StartStreamRequest(null, null, null, null, null, null);

    /**
     * Merges this request onto {@link PipelineConfig#defaults()}.
     *
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOntoDefaults() {
        PipelineConfig defaults = PipelineConfig.defaults();
        double confidence = confidenceThreshold != null ? confidenceThreshold : defaults.confidenceThreshold();
        int fps = inferenceFps != null ? inferenceFps : defaults.inferenceFps();
        boolean burnIn = overlayBurnIn != null ? overlayBurnIn : defaults.overlayBurnIn();
        ModelRef effectiveModel =
                model != null && !model.isBlank() ? new ModelRef(model, defaults.model().version()) : defaults.model();
        Set<String> effectiveLabelFilter = labelFilter != null ? Set.copyOf(labelFilter) : defaults.labelFilter();
        boolean effectiveDetectionEnabled =
                detectionEnabled != null ? detectionEnabled : defaults.detectionEnabled();
        return new PipelineConfig(effectiveModel, confidence, fps, defaults.maxInFlightInferences(),
                defaults.overlayTelemetry(), effectiveLabelFilter, defaults.eventRule(), burnIn,
                effectiveDetectionEnabled);
    }
}
