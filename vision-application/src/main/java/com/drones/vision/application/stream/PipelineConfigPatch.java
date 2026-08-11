package com.drones.vision.application.stream;

import java.util.Set;
import com.drones.vision.application.asset.AssetEdit;
import com.drones.vision.application.device.DeviceEdit;
import com.drones.vision.application.pipeline.StreamPipeline;
import com.drones.vision.domain.model.TrackingConfig;

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
 * <p>{@code tracking}, when present, replaces the running {@link TrackingConfig} wholesale — mode,
 * engine and every cadence — with two deliberate exceptions inside it (docs/TRACKING-PLAN.md
 * &sect;4.D):
 * <ul>
 *   <li><b>{@code lock} is itself null-means-unchanged.</b> A present {@code tracking} whose {@code
 *       lock} is {@code null} keeps whatever lock the stream is holding, so adjusting the mode or a
 *       cadence never silently drops the operator's target. Dropping a lock is the explicit {@code
 *       release} form of {@link com.drones.vision.domain.model.TargetLock}, never an omission.</li>
 *   <li><b>{@code lock.lockSeq} is ignored and replaced.</b> A client never allocates one; {@link
 *       DefaultStreamService#updateConfig} stamps a fresh value from the stream's own monotonic
 *       counter. That is what makes a replayed stale lock a no-op rather than a resurrection of an
 *       abandoned target. Callers building this record should leave it {@code 0}.</li>
 * </ul>
 *
 * @param confidenceThreshold replacement confidence threshold, or {@code null} to keep the current one
 * @param inferenceFps        replacement inference sample rate, or {@code null} to keep the current one
 * @param labelFilter         replacement label set, or {@code null} to keep the current one
 * @param detectionEnabled    replacement detection on/off flag, or {@code null} to keep the current one
 * @param modelId             replacement model checkpoint id, or {@code null} to keep the current one
 * @param tracking            replacement tracking configuration, or {@code null} to keep the current
 *                            one; see this record's own javadoc for how its {@code lock} folds
 */
public record PipelineConfigPatch(Double confidenceThreshold, Integer inferenceFps, Set<String> labelFilter,
                                   Boolean detectionEnabled, String modelId, TrackingConfig tracking) {

    /** A patch that changes nothing — the identity of this operation. */
    public static final PipelineConfigPatch NOTHING = new PipelineConfigPatch(null, null, null, null, null, null);

    /**
     * The canonical constructor before docs/TRACKING-PLAN.md wave T3 added {@code tracking}, kept as
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
