package com.drones.vision.api.dto;

import com.drones.vision.application.stream.PipelineConfigPatch;

import java.util.List;
import java.util.Set;

/**
 * Request body for {@code PATCH /api/streams/{streamId}/config} (docs/CV-CONTROL-PLAN.md §3's frozen
 * wire contract) — a live, partial update to a running stream's detection config.
 *
 * <p>Every field is optional; only present fields change, absent fields are left as-is. This is a
 * true partial patch, not a full replace — the same null-means-unchanged idiom {@link
 * UpdateDeviceRequest}/{@link UpdateAssetRequest} already use for their own {@code PATCH} bodies.
 *
 * <p>Confidence threshold, inference fps, label filter, and detection on/off all apply live with no
 * video interruption. A present {@code model} that differs from the stream's currently-running model
 * briefly re-arms detection instead (see {@link UpdateStreamConfigResponse#modelReArmed()}) — video
 * is untouched either way. {@code maxInFlightInferences}, {@code overlayTelemetry}, {@code
 * overlayBurnIn}, {@code eventRule}, and the model's {@code version} are deliberately not exposed
 * here — not PATCH-able in v1 (frozen contract §3).
 *
 * @param confidenceThreshold replacement confidence threshold, or absent to keep the current one
 * @param inferenceFps        replacement inference sample rate, or absent to keep the current one
 * @param labelFilter         replacement label set (JSON array), or absent to keep the current one; an
 *                            explicit empty array is a real value meaning "keep all labels"
 * @param detectionEnabled    replacement detection on/off flag, or absent to keep the current one
 * @param model               replacement model checkpoint id, or absent to keep the current one
 */
public record UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                         Boolean detectionEnabled, String model) {

    /** No body at all: a patch that changes nothing. */
    public static final UpdateStreamConfigRequest EMPTY =
            new UpdateStreamConfigRequest(null, null, null, null, null);

    /**
     * Maps this request to the application-level patch.
     *
     * @return the partial patch to apply
     */
    public PipelineConfigPatch toPatch() {
        Set<String> filter = labelFilter == null ? null : Set.copyOf(labelFilter);
        return new PipelineConfigPatch(confidenceThreshold, inferenceFps, filter, detectionEnabled, model);
    }
}
