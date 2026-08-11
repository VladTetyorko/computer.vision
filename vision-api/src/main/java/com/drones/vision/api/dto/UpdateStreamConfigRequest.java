package com.drones.vision.api.dto;

import com.drones.vision.application.stream.PipelineConfigPatch;
import com.drones.vision.domain.model.TrackingConfig;

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
 * @param tracking            replacement tracking configuration (docs/TRACKING-PLAN.md §4.D), or
 *                            absent to leave tracking entirely alone. <b>A tracking change never
 *                            re-arms the detector</b> — mode, engine, cadences and the target lock
 *                            are all hot knobs, exactly like confidence and fps; only {@code model}
 *                            ever re-arms
 */
public record UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                         Boolean detectionEnabled, String model, TrackingConfigRequest tracking) {

    /** No body at all: a patch that changes nothing. */
    public static final UpdateStreamConfigRequest EMPTY =
            new UpdateStreamConfigRequest(null, null, null, null, null, null);

    /**
     * The canonical constructor before docs/TRACKING-PLAN.md wave T6 added {@code tracking}, kept as
     * a convenience constructor defaulting it to {@code null} ("leave tracking alone") — the same
     * N-1-arg idiom the application's own {@code PipelineConfigPatch} uses.
     *
     * @param confidenceThreshold replacement confidence threshold, or {@code null}
     * @param inferenceFps        replacement inference sample rate, or {@code null}
     * @param labelFilter         replacement label set, or {@code null}
     * @param detectionEnabled    replacement detection on/off flag, or {@code null}
     * @param model               replacement model checkpoint id, or {@code null}
     */
    public UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                      Boolean detectionEnabled, String model) {
        this(confidenceThreshold, inferenceFps, labelFilter, detectionEnabled, model, null);
    }

    /**
     * Maps this request to the application-level patch, with tracking left untouched.
     *
     * @return the partial patch to apply
     * @throws IllegalArgumentException if a {@code tracking} object is present but invalid (→400)
     */
    public PipelineConfigPatch toPatch() {
        return toPatch(TrackingConfig.off());
    }

    /**
     * Maps this request to the application-level patch (docs/TRACKING-PLAN.md §4.D).
     *
     * <p>{@code trackingBase} is what an <b>absent</b> field inside a present {@code tracking}
     * object falls back to — see {@link TrackingConfigRequest} for the merge and {@code
     * StreamController#updateConfig} for how the base is obtained from the running stream. An
     * absent {@code tracking} object ignores it entirely and leaves the patch's {@code tracking}
     * {@code null}, which the application layer reads as "leave tracking alone".
     *
     * @param trackingBase the running stream's readable tracking state; never {@code null}
     * @return the partial patch to apply
     * @throws IllegalArgumentException if a {@code tracking} object is present but invalid (→400)
     */
    public PipelineConfigPatch toPatch(TrackingConfig trackingBase) {
        Set<String> filter = labelFilter == null ? null : Set.copyOf(labelFilter);
        TrackingConfig trackingConfig = tracking == null ? null : tracking.toTrackingConfig(trackingBase);
        return new PipelineConfigPatch(confidenceThreshold, inferenceFps, filter, detectionEnabled, model,
                trackingConfig);
    }
}
