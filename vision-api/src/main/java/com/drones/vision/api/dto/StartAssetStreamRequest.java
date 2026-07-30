package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;

import java.util.List;

/**
 * Optional request body for {@code POST /api/assets/{id}/stream}.
 *
 * <p>All fields are optional. {@code deviceId}, if present, selects which of
 * the asset's devices to stream from; if absent, {@code AssetService}
 * resolves the asset's single {@code VIDEO}-capable device, throwing
 * {@link IllegalArgumentException} (surfaced as 400) if that
 * is ambiguous. The rest of the fields override the corresponding
 * {@link PipelineConfig#defaults()} values, delegating to
 * {@link StartStreamRequest#mergeOntoDefaults()} for that merge so the two
 * start-stream request shapes share one implementation.
 *
 * @param deviceId            the device to stream from, as a canonical UUID string; {@code null} means "the asset's single video-capable device"
 * @param confidenceThreshold overrides {@link PipelineConfig#confidenceThreshold()} if present
 * @param inferenceFps        overrides {@link PipelineConfig#inferenceFps()} if present
 * @param overlayBurnIn       overrides {@link PipelineConfig#overlayBurnIn()} if present (docs/MVP2-PLAN.md §V, V-e)
 * @param model               overrides {@link PipelineConfig#model()}'s {@code id} if present/non-blank — see
 *                             {@link StartStreamRequest#model()}'s own javadoc for the full contract (raw,
 *                             never split; version comes from the default)
 * @param labelFilter         overrides {@link PipelineConfig#labelFilter()} if present — see {@link
 *                             StartStreamRequest#labelFilter()}'s own javadoc (docs/CV-CONTROL-PLAN.md §2)
 * @param detectionEnabled    overrides {@link PipelineConfig#detectionEnabled()} if present (docs/CV-CONTROL-PLAN.md §2)
 */
public record StartAssetStreamRequest(String deviceId, Double confidenceThreshold, Integer inferenceFps,
                                       Boolean overlayBurnIn, String model, List<String> labelFilter,
                                       Boolean detectionEnabled) {

    /** No body: no explicit device, use every default from {@link PipelineConfig#defaults()}. */
    public static final StartAssetStreamRequest EMPTY =
            new StartAssetStreamRequest(null, null, null, null, null, null, null);

    /**
     * Parses {@link #deviceId()}, if present.
     *
     * @return the parsed device id, or {@code null} if {@link #deviceId()} is absent
     * @throws IllegalArgumentException if {@link #deviceId()} is present but not a valid UUID string
     */
    public DeviceId deviceIdOrNull() {
        return deviceId == null ? null : DeviceId.of(deviceId);
    }

    /**
     * Merges {@link #confidenceThreshold()}/{@link #inferenceFps()}/{@link #overlayBurnIn()}/{@link
     * #model()}/{@link #labelFilter()}/{@link #detectionEnabled()} onto {@link PipelineConfig#defaults()}.
     *
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOntoDefaults() {
        return new StartStreamRequest(confidenceThreshold, inferenceFps, overlayBurnIn, model, labelFilter,
                detectionEnabled).mergeOntoDefaults();
    }
}
