package com.drones.vision.application;

import java.util.Set;

/**
 * A partial live update to a running stream's {@link com.drones.vision.domain.model.PipelineConfig}
 * (docs/CV-CONTROL-PLAN.md &sect;5, the frozen {@code PATCH /api/streams/{streamId}/config}
 * contract): every field is optional, {@code null} means "leave this knob unchanged" — the same
 * null-means-unchanged partial-edit idiom {@link AssetEdit}/{@link DeviceEdit} already use.
 * {@link DefaultStreamService#updateConfig} folds only the present fields onto the stream's
 * current config; the merged result is validated by {@link
 * com.drones.vision.domain.model.PipelineConfig}'s own compact constructor, so an out-of-range
 * value surfaces as {@link IllegalArgumentException} without this record needing to duplicate that
 * validation.
 *
 * <p>{@code modelId} carries only the model checkpoint id, never the version — {@link
 * com.drones.vision.domain.model.PipelineConfig#model()}'s version component is deliberately not
 * PATCH-able (frozen contract &sect;3); a present {@code modelId} keeps the running config's
 * current version unchanged. {@code maxInFlightInferences}, {@code overlayTelemetry}, {@code
 * overlayBurnIn}, and {@code eventRule} are likewise not PATCH-able in v1 and have no field here at
 * all — see {@code StreamPipeline}'s javadoc and vision-application/MODULE.md for why.
 *
 * <p>{@code labelFilter}, when present, replaces the running set wholesale — there is no per-label
 * add/remove, only present-vs-absent for the set as a whole; an empty (but non-{@code null}) set is
 * a real value meaning "keep all labels", the same semantics an empty {@code labelFilter} already
 * has on {@code PipelineConfig} itself.
 *
 * @param confidenceThreshold replacement confidence threshold, or {@code null} to keep the current one
 * @param inferenceFps        replacement inference sample rate, or {@code null} to keep the current one
 * @param labelFilter         replacement label set, or {@code null} to keep the current one
 * @param detectionEnabled    replacement detection on/off flag, or {@code null} to keep the current one
 * @param modelId             replacement model checkpoint id, or {@code null} to keep the current one
 */
public record PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                   Boolean detectionEnabled, String modelId) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final PipelineConfigPatch NOTHING = new PipelineConfigPatch(null, null, null, null, null);

    public PipelineConfigPatch {
        if (labelFilter != null) {
            labelFilter = Set.copyOf(labelFilter);
        }
    }
}
