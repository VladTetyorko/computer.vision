package com.drones.vision.api.dto;

import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.PipelineConfig;
import com.drones.vision.domain.model.TrackingConfig;

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
 * @param tracking            overrides the deployment's tracking seed if present — see {@link
 *                             StartStreamRequest#tracking()}'s own javadoc for the full contract
 *                             (same shape as {@code PATCH .../config}'s, minus {@code lock})
 */
public record StartAssetStreamRequest(String deviceId, Double confidenceThreshold, Integer inferenceFps,
                                       Boolean overlayBurnIn, String model, List<String> labelFilter,
                                       Boolean detectionEnabled, TrackingConfigRequest tracking) {

    /** No body: no explicit device, use every default from {@link PipelineConfig#defaults()}. */
    public static final StartAssetStreamRequest EMPTY =
            new StartAssetStreamRequest(null, null, null, null, null, null, null, null);

    /**
     * The canonical constructor before docs/TRACKING-PLAN.md wave T6 added {@code tracking}, kept as
     * a convenience constructor defaulting it to {@code null} ("use the seed as-is").
     *
     * @param deviceId            the device to stream from, or {@code null}
     * @param confidenceThreshold overrides the default confidence threshold if present
     * @param inferenceFps        overrides the default inference sample rate if present
     * @param overlayBurnIn       overrides the default burn-in flag if present
     * @param model               overrides the default model id if present/non-blank
     * @param labelFilter         overrides the default label set if present
     * @param detectionEnabled    overrides the default detection on/off flag if present
     */
    public StartAssetStreamRequest(String deviceId, Double confidenceThreshold, Integer inferenceFps,
                                    Boolean overlayBurnIn, String model, List<String> labelFilter,
                                    Boolean detectionEnabled) {
        this(deviceId, confidenceThreshold, inferenceFps, overlayBurnIn, model, labelFilter, detectionEnabled, null);
    }

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
     * #model()}/{@link #labelFilter()}/{@link #detectionEnabled()}/{@link #tracking()} onto {@link
     * PipelineConfig#defaults()}.
     *
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOntoDefaults() {
        return mergeOntoDefaults(PipelineConfig.defaults().tracking());
    }

    /**
     * {@link #mergeOntoDefaults()} with the deployment's tracking seed — see {@link
     * StartStreamRequest#mergeOntoDefaults(TrackingConfig)}, which this delegates to so the two
     * start-stream shapes keep sharing exactly one merge implementation.
     *
     * @param trackingSeed the deployment's tracking defaults for new streams; never {@code null}
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOntoDefaults(TrackingConfig trackingSeed) {
        return new StartStreamRequest(confidenceThreshold, inferenceFps, overlayBurnIn, model, labelFilter,
                detectionEnabled, tracking).mergeOntoDefaults(trackingSeed);
    }
}
