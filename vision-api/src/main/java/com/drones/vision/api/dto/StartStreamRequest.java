package com.drones.vision.api.dto;

import com.drones.vision.domain.model.PipelineConfig;

/**
 * Optional request body for {@code POST /api/devices/{deviceId}/stream}.
 *
 * <p>Both fields are optional; each present field overrides the
 * corresponding value from {@link PipelineConfig#defaults()}. All other
 * settings (model, max in-flight inferences, telemetry overlay, label
 * filter) come from the defaults untouched — Phase 1 only exposes the two
 * settings a dev-console user is likely to want to tweak (KISS).
 *
 * @param confidenceThreshold overrides {@link PipelineConfig#confidenceThreshold()} if present
 * @param inferenceFps        overrides {@link PipelineConfig#inferenceFps()} if present
 */
public record StartStreamRequest(Double confidenceThreshold, Integer inferenceFps) {

    /** No overrides: use every default from {@link PipelineConfig#defaults()}. */
    public static final StartStreamRequest EMPTY = new StartStreamRequest(null, null);

    /**
     * Merges this request onto {@link PipelineConfig#defaults()}.
     *
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOntoDefaults() {
        PipelineConfig defaults = PipelineConfig.defaults();
        double confidence = confidenceThreshold != null ? confidenceThreshold : defaults.confidenceThreshold();
        int fps = inferenceFps != null ? inferenceFps : defaults.inferenceFps();
        return new PipelineConfig(defaults.model(), confidence, fps, defaults.maxInFlightInferences(),
                defaults.overlayTelemetry(), defaults.labelFilter(), defaults.eventRule());
    }
}
