package com.drones.vision.api.dto;

import com.drones.vision.perception.application.stream.TrackingConfigPatch;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.perception.domain.model.PipelineConfig;

import java.util.List;

/**
 * Optional request body for {@code POST /api/assets/{id}/stream}.
 *
 * <p>All fields are optional. {@code deviceId}, if present, selects which of
 * the asset's devices to stream from; if absent, {@code AssetService}
 * resolves the asset's single {@code VIDEO}-capable device, throwing
 * {@link IllegalArgumentException} (surfaced as 400) if that
 * is ambiguous. The rest of the fields override the corresponding
 * deployment-default {@link PipelineConfig} values, delegating to
 * {@link StartStreamRequest#mergeOnto(PipelineConfig)} for that merge so the two
 * start-stream request shapes share one implementation.
 *
 * @param deviceId            the device to stream from, as a canonical UUID string; {@code null} means "the asset's single video-capable device"
 * @param confidenceThreshold overrides {@link PipelineConfig#confidenceThreshold()} if present
 * @param inferenceFps        overrides {@link PipelineConfig#inferenceFps()} if present
 * @param model               overrides {@link PipelineConfig#model()}'s {@code id} if present/non-blank — see
 *                             {@link StartStreamRequest#model()}'s own javadoc for the full contract (raw,
 *                             never split; version comes from the default)
 * @param labelFilter         overrides {@link PipelineConfig#labelFilter()} if present — see {@link
 *                             StartStreamRequest#labelFilter()}'s own javadoc (docs/plans/done/CV-CONTROL-PLAN.md §2)
 * @param labelDenyFilter      overrides {@link PipelineConfig#labelDenyFilter()} if present — see {@link
 *                             StartStreamRequest#labelDenyFilter()}'s own javadoc (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2)
 * @param detectionEnabled    overrides {@link PipelineConfig#detectionEnabled()} if present (docs/plans/done/CV-CONTROL-PLAN.md §2)
 * @param tracking            overrides the deployment's tracking seed if present — see {@link
 *                             StartStreamRequest#tracking()}'s own javadoc for the full contract
 *                             (same shape as {@code PATCH .../config}'s, minus {@code lock})
 */
public record StartAssetStreamRequest(String deviceId, Double confidenceThreshold, Integer inferenceFps,
                                       String model, List<String> labelFilter, List<String> labelDenyFilter,
                                       Boolean detectionEnabled, TrackingConfigRequest tracking) {

    /** No body: no explicit device, use every default from {@link PipelineConfig#defaults()}. */
    public static final StartAssetStreamRequest EMPTY =
            new StartAssetStreamRequest(null, null, null, null, null, null, null, null);

    /**
     * Convenience constructor defaulting {@code labelDenyFilter} to {@code null} ("deny nothing beyond
     * the default").
     *
     * @param deviceId            the device to stream from, or {@code null}
     * @param confidenceThreshold overrides the default confidence threshold if present
     * @param inferenceFps        overrides the default inference sample rate if present
     * @param model               overrides the default model id if present/non-blank
     * @param labelFilter         overrides the default label set if present
     * @param detectionEnabled    overrides the default detection on/off flag if present
     * @param tracking            overrides the deployment's tracking seed if present
     */
    public StartAssetStreamRequest(String deviceId, Double confidenceThreshold, Integer inferenceFps,
                                    String model, List<String> labelFilter, Boolean detectionEnabled,
                                    TrackingConfigRequest tracking) {
        this(deviceId, confidenceThreshold, inferenceFps, model, labelFilter, null, detectionEnabled, tracking);
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
     * Merges {@link #confidenceThreshold()}/{@link #inferenceFps()}/{@link #model()}/{@link
     * #labelFilter()}/{@link #labelDenyFilter()}/{@link #detectionEnabled()} onto {@code defaults},
     * delegating to {@link StartStreamRequest#mergeOnto(PipelineConfig)} so the two start-stream
     * shapes keep sharing exactly one merge implementation. Tracking travels separately as {@link
     * #trackingPatch()} — see that method and its device-level twin.
     *
     * @param defaults the deployment's default pipeline configuration to merge this request onto
     *                 (docs/plans/active/CV-DEMAND-PLAN.md §3.7/§3.8)
     * @return the effective pipeline configuration for the new stream
     */
    public PipelineConfig mergeOnto(PipelineConfig defaults) {
        return asDeviceLevelRequest().mergeOnto(defaults);
    }

    /**
     * What this request states about tracking, per field — exactly {@link
     * StartStreamRequest#trackingPatch()}.
     *
     * @return the tracking patch to fold onto the deployment seed; never {@code null}
     * @throws IllegalArgumentException if the {@code tracking} object is invalid, or carries a
     *                                  {@code lock} (→400)
     */
    public TrackingConfigPatch trackingPatch() {
        return asDeviceLevelRequest().trackingPatch();
    }

    private StartStreamRequest asDeviceLevelRequest() {
        return new StartStreamRequest(confidenceThreshold, inferenceFps, model, labelFilter, labelDenyFilter,
                detectionEnabled, tracking);
    }
}
