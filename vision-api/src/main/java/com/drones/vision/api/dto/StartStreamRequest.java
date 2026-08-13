package com.drones.vision.api.dto;

import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.PipelineConfig;

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
 * (docs/plans/done/MVP2-PLAN.md §V, V-e), {@code model} (the frontend's detection-model
 * picker), and {@code labelFilter}/{@code detectionEnabled}
 * (docs/plans/done/CV-CONTROL-PLAN.md §2) were added the same way, per-stream,
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
 * @param tracking            overrides the deployment's tracking seed if present (docs/plans/done/TRACKING-PLAN.md
 *                             §4.D) — the same object shape {@code PATCH .../config} accepts
 *                             <b>minus {@code lock}</b>, which names a track that cannot exist before
 *                             the stream has produced one and is therefore a 400 here; absent keeps
 *                             the seed exactly
 */
public record StartStreamRequest(Double confidenceThreshold, Integer inferenceFps, Boolean overlayBurnIn,
                                  String model, List<String> labelFilter, Boolean detectionEnabled,
                                  TrackingConfigRequest tracking) {

    /** No overrides: use every default from {@link PipelineConfig#defaults()}. */
    public static final StartStreamRequest EMPTY = new StartStreamRequest(null, null, null, null, null, null, null);

    /**
     * The canonical constructor before docs/plans/done/TRACKING-PLAN.md wave T6 added {@code tracking}, kept as
     * a convenience constructor defaulting it to {@code null} ("use the seed as-is").
     *
     * @param confidenceThreshold overrides the default confidence threshold if present
     * @param inferenceFps        overrides the default inference sample rate if present
     * @param overlayBurnIn       overrides the default burn-in flag if present
     * @param model               overrides the default model id if present/non-blank
     * @param labelFilter         overrides the default label set if present
     * @param detectionEnabled    overrides the default detection on/off flag if present
     */
    public StartStreamRequest(Double confidenceThreshold, Integer inferenceFps, Boolean overlayBurnIn, String model,
                               List<String> labelFilter, Boolean detectionEnabled) {
        this(confidenceThreshold, inferenceFps, overlayBurnIn, model, labelFilter, detectionEnabled, null);
    }

    /**
     * Merges this request onto {@link PipelineConfig#defaults()}.
     *
     * <p>The tracking component stays the domain's own default here — it is the <b>bottom</b> layer
     * of the fold, not the answer. What this request states about tracking travels separately as
     * {@link #trackingPatch()}, and the application layer composes the three layers (request &gt;
     * deployment seed &gt; this default) when the stream starts, so that every start path seeds
     * identically (docs/extracts/TRACKING-ORCHESTRATION.md §4.1).
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
                effectiveDetectionEnabled, defaults.tracking());
    }

    /**
     * What this request states about tracking, per field (docs/plans/done/TRACKING-PLAN.md §4.D) — an absent
     * {@code tracking} object states nothing.
     *
     * @return the tracking patch to fold onto the deployment seed; never {@code null}
     * @throws IllegalArgumentException if the {@code tracking} object is invalid, or carries a
     *                                  {@code lock} — which names a track that cannot exist before
     *                                  the stream has produced one (→400)
     */
    public TrackingConfigPatch trackingPatch() {
        return tracking == null ? TrackingConfigPatch.NOTHING : tracking.toStartPatch();
    }
}
