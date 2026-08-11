package com.drones.vision.application.stream;

import java.util.Set;
import com.drones.vision.application.pipeline.StreamPipeline;
import com.drones.vision.perception.domain.model.TrackingConfig;

/**
 * A partial live update to a running stream's {@link com.drones.vision.perception.domain.model.PipelineConfig}
 * (docs/plans/done/CV-CONTROL-PLAN.md &sect;5, the frozen {@code PATCH /api/streams/{streamId}/config}
 * contract): every field is optional, {@code null} means "leave this knob unchanged" — the same
 * null-means-unchanged partial-edit idiom {@link com.drones.vision.application.asset.AssetEdit}/{@link com.drones.vision.application.device.DeviceEdit} already use.
 * {@link DefaultStreamService#updateConfig} folds only the present fields onto the stream's
 * current config; the merged result is validated by {@link
 * com.drones.vision.perception.domain.model.PipelineConfig}'s own compact constructor, so an out-of-range
 * value surfaces as {@link IllegalArgumentException} without this record needing to duplicate that
 * validation.
 *
 * <p>{@code modelId} carries only the model checkpoint id, never the version — {@link
 * com.drones.vision.perception.domain.model.PipelineConfig#model()}'s version component is deliberately not
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
 * <p>{@code tracking} is itself a <b>partial</b> statement — a {@link TrackingConfigPatch}, not a
 * whole {@link TrackingConfig} (docs/plans/done/TRACKING-PLAN.md &sect;4.D). {@code null} leaves tracking
 * entirely alone; a present patch changes only the knobs it names, because the UI adjusts them one
 * at a time and a whole-value replace made every partial patch silently reset the seven knobs it did
 * not mention. {@link TrackingConfigPatch} carries the fold and documents the two knobs whose
 * semantics are not "replace if present" — the target {@code lock}, whose absence never means
 * release, and its {@code lockSeq}, which {@link DefaultStreamService#updateConfig} always allocates
 * server-side.
 *
 * @param confidenceThreshold replacement confidence threshold, or {@code null} to keep the current one
 * @param inferenceFps        replacement inference sample rate, or {@code null} to keep the current one
 * @param labelFilter         replacement label set, or {@code null} to keep the current one
 * @param detectionEnabled    replacement detection on/off flag, or {@code null} to keep the current one
 * @param modelId             replacement model checkpoint id, or {@code null} to keep the current one
 * @param tracking            per-field tracking changes, or {@code null} to leave tracking entirely
 *                            alone; see {@link TrackingConfigPatch} for how each field folds
 */
public record PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                   Boolean detectionEnabled, String modelId, TrackingConfigPatch tracking) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final PipelineConfigPatch NOTHING = new PipelineConfigPatch(null, null, null, null, null, null);

    /**
     * The canonical constructor before docs/plans/done/TRACKING-PLAN.md wave T3 added {@code tracking}, kept as
     * a convenience constructor defaulting it to {@code null} ("leave tracking alone"), so every
     * pre-existing call site — {@code vision-api}'s {@code UpdateStreamConfigRequest#toPatch()}
     * among them — compiles unchanged. Same "N-1-arg convenience ctor" idiom the domain's {@code
     * PipelineConfig}/{@code Detection}/{@code DetectionResult} already use.
     */
    public PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                Boolean detectionEnabled, String modelId) {
        this(confidenceThreshold, inferenceFps, labelFilter, detectionEnabled, modelId, null);
    }

    public PipelineConfigPatch {
        if (labelFilter != null) {
            labelFilter = Set.copyOf(labelFilter);
        }
    }
}
