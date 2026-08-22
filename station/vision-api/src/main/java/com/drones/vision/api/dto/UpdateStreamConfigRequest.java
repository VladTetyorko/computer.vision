package com.drones.vision.api.dto;

import com.drones.vision.perception.application.stream.PipelineConfigPatch;

import java.util.List;
import java.util.Set;

/**
 * Request body for {@code PATCH /api/streams/{streamId}/config} (docs/plans/done/CV-CONTROL-PLAN.md §3's frozen
 * wire contract) — a live, partial update to a running stream's detection config.
 *
 * <p>Every field is optional; only present fields change, absent fields are left as-is. This is a
 * true partial patch, not a full replace — the same null-means-unchanged idiom {@link
 * UpdateDeviceRequest}/{@link UpdateAssetRequest} already use for their own {@code PATCH} bodies.
 *
 * <p>Confidence threshold, inference fps, label filter/deny filter, and detection on/off all apply
 * live with no video interruption. A present {@code model} that differs from the stream's
 * currently-running model briefly re-arms detection instead (see {@link
 * UpdateStreamConfigResponse#modelReArmed()}) — video is untouched either way. {@code
 * maxInFlightInferences} and {@code eventRule}, and the model's {@code version}, are deliberately
 * not exposed here — not PATCH-able in v1 (frozen contract §3). {@code labelDenyFilter}
 * (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2) is the one field added since that freeze, mirroring
 * {@code labelFilter}'s own semantics one for one.
 *
 * @param confidenceThreshold replacement confidence threshold, or absent to keep the current one
 * @param inferenceFps        replacement inference sample rate, or absent to keep the current one
 * @param labelFilter         replacement label set (JSON array), or absent to keep the current one; an
 *                            explicit empty array is a real value meaning "keep all labels"
 * @param labelDenyFilter     replacement label deny list (JSON array), or absent to keep the current
 *                            one; an explicit empty array is a real value meaning "deny nothing"
 * @param detectionEnabled    replacement detection on/off flag, or absent to keep the current one
 * @param model               replacement model checkpoint id, or absent to keep the current one
 * @param tracking            replacement tracking configuration (docs/plans/done/TRACKING-PLAN.md §4.D), or
 *                            absent to leave tracking entirely alone. <b>A tracking change never
 *                            re-arms the detector</b> — mode, engine, cadences and the target lock
 *                            are all hot knobs, exactly like confidence and fps; only {@code model}
 *                            ever re-arms
 */
public record UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                         List<String> labelDenyFilter, Boolean detectionEnabled, String model,
                                         TrackingConfigRequest tracking) {

    /** No body at all: a patch that changes nothing. */
    public static final UpdateStreamConfigRequest EMPTY =
            new UpdateStreamConfigRequest(null, null, null, null, null, null, null);

    /**
     * Convenience constructor defaulting {@code labelDenyFilter} to {@code null} ("leave the deny
     * list alone") — the same N-1-arg idiom the application's own {@code PipelineConfigPatch} uses.
     *
     * @param confidenceThreshold replacement confidence threshold, or {@code null}
     * @param inferenceFps        replacement inference sample rate, or {@code null}
     * @param labelFilter         replacement label set, or {@code null}
     * @param detectionEnabled    replacement detection on/off flag, or {@code null}
     * @param model               replacement model checkpoint id, or {@code null}
     * @param tracking            replacement tracking configuration, or {@code null}
     */
    public UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                      Boolean detectionEnabled, String model, TrackingConfigRequest tracking) {
        this(confidenceThreshold, inferenceFps, labelFilter, null, detectionEnabled, model, tracking);
    }

    /**
     * Maps this request to the application-level patch (docs/plans/done/TRACKING-PLAN.md §4.D), field for
     * field: an absent JSON field is a {@code null} the application layer reads as "leave this knob
     * unchanged", and an absent {@code tracking} object leaves tracking entirely alone.
     *
     * <p>Nothing is merged here on purpose. The running configuration is the application layer's
     * state; this edge cannot read it back, and a controller that reconstructed it from a read model
     * could only ever restore the fields that happen to be observable — which is exactly how the
     * cadence knobs used to get reset by an unrelated patch.
     *
     * @return the partial patch to apply
     * @throws IllegalArgumentException if a {@code tracking} object is present but invalid (→400)
     */
    public PipelineConfigPatch toPatch() {
        Set<String> filter = labelFilter == null ? null : Set.copyOf(labelFilter);
        Set<String> denyFilter = labelDenyFilter == null ? null : Set.copyOf(labelDenyFilter);
        return new PipelineConfigPatch(confidenceThreshold, inferenceFps, filter, detectionEnabled, model,
                tracking == null ? null : tracking.toPatch(), denyFilter);
    }
}
