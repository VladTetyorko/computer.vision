package com.drones.vision.perception.application.stream;

import java.util.Set;
import com.drones.vision.perception.application.pipeline.StreamPipeline;
import com.drones.vision.perception.domain.model.TrackingConfig;

/**
 * A partial live update to a running stream's {@link com.drones.vision.perception.domain.model.PipelineConfig}
 * (docs/plans/done/CV-CONTROL-PLAN.md &sect;5, the frozen {@code PATCH /api/streams/{streamId}/config}
 * contract): every field is optional, {@code null} means "leave this knob unchanged" — the same
 * null-means-unchanged partial-edit idiom {@link com.drones.vision.warehouse.application.asset.AssetEdit}/{@link com.drones.vision.warehouse.application.device.DeviceEdit} already use.
 * {@link DefaultStreamService#updateConfig} folds only the present fields onto the stream's
 * current config; the merged result is validated by {@link
 * com.drones.vision.perception.domain.model.PipelineConfig}'s own compact constructor, so an out-of-range
 * value surfaces as {@link IllegalArgumentException} without this record needing to duplicate that
 * validation.
 *
 * <p>{@code modelId} carries only the model checkpoint id, never the version — {@link
 * com.drones.vision.perception.domain.model.PipelineConfig#model()}'s version component is deliberately not
 * PATCH-able (frozen contract &sect;3); a present {@code modelId} keeps the running config's
 * current version unchanged. {@code maxInFlightInferences} and {@code eventRule} are likewise not
 * PATCH-able in v1 and have no field here at all — see {@code StreamPipeline}'s javadoc and
 * contexts/vision-perception/MODULE.md for why.
 *
 * <p>{@code labelFilter}/{@code labelDenyFilter}, when present, each replace their running set
 * wholesale — there is no per-label add/remove, only present-vs-absent for the set as a whole; an
 * empty (but non-{@code null}) set is a real value, meaning "keep all labels"/"deny nothing"
 * respectively, the same semantics an empty set already has on {@code PipelineConfig} itself.
 * {@code labelDenyFilter} (docs/plans/active/CV-CLEAN-FEED-PLAN.md &sect;2, D-2) is the honest
 * "hide this class" act: it never touches {@code labelFilter}, so classes not yet observed keep
 * appearing instead of being silently swept into an allowlist complement.
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
 * @param labelFilter         replacement label allowlist, or {@code null} to keep the current one
 * @param detectionEnabled    replacement detection on/off flag, or {@code null} to keep the current one
 * @param modelId             replacement model checkpoint id, or {@code null} to keep the current one
 * @param tracking            per-field tracking changes, or {@code null} to leave tracking entirely
 *                            alone; see {@link TrackingConfigPatch} for how each field folds
 * @param labelDenyFilter     replacement label deny-list, or {@code null} to keep the current one
 */
public record PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                   Boolean detectionEnabled, String modelId, TrackingConfigPatch tracking,
                                   Set<String> labelDenyFilter) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final PipelineConfigPatch NOTHING =
            new PipelineConfigPatch(null, null, null, null, null, null, null);

    /**
     * Convenience constructor for callers that don't care about {@link #labelDenyFilter()} —
     * defaults it to {@code null} ("leave unchanged"), the same "N-1-arg convenience ctor" idiom
     * used elsewhere. This was the canonical constructor before
     * docs/plans/active/CV-CLEAN-FEED-PLAN.md &sect;2 added {@link #labelDenyFilter()}; every
     * pre-existing 6-arg call site compiles unchanged.
     */
    public PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                Boolean detectionEnabled, String modelId, TrackingConfigPatch tracking) {
        this(confidenceThreshold, inferenceFps, labelFilter, detectionEnabled, modelId, tracking, null);
    }

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
        if (labelDenyFilter != null) {
            labelDenyFilter = Set.copyOf(labelDenyFilter);
        }
    }
}
