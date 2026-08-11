package com.drones.vision.perception.application.stream;

/**
 * Result of {@link StreamService#updateConfig} (docs/plans/done/CV-CONTROL-PLAN.md &sect;5,
 * docs/plans/done/TRACKING-PLAN.md &sect;4.D).
 *
 * @param modelReArmed    {@code true} only when the patch carried a {@code modelId} (see {@link
 *                        PipelineConfigPatch#modelId()}) that differed from the stream's running
 *                        model — the caller (vision-api) surfaces this so the operator knows a
 *                        brief detection gap occurred while the detector swaps to the new model.
 *                        Every other knob is hot and never re-arms (frozen contract &sect;3): a
 *                        patch that only touches confidence/inference-fps/label-filter/detection-on-
 *                        off/<b>tracking</b> reports {@code false}.
 * @param trackingChanged {@code true} iff the patch carried a {@code tracking} object <b>and</b> the
 *                        fold produced a different {@link com.drones.vision.perception.domain.model.TrackingConfig}
 *                        than the one already running — so restating the identical configuration
 *                        reports {@code false}, while re-issuing a lock reports {@code true} (its
 *                        server-allocated {@code lockSeq} is new, which is exactly what makes it a
 *                        fresh request rather than a replay). <b>A mode or engine change never
 *                        re-arms the detector</b>: tracking is a hot knob throughout, exactly like
 *                        confidence and fps, so the two flags are independent and both can be
 *                        {@code true} in one call.
 */
public record UpdateOutcome(boolean modelReArmed, boolean trackingChanged) {

    /**
     * The canonical constructor before docs/plans/done/TRACKING-PLAN.md wave T3 added {@code trackingChanged},
     * kept as a convenience constructor defaulting it to {@code false}, so pre-existing call sites
     * (vision-api's controller tests stub this type) compile unchanged. Same "N-1-arg convenience
     * ctor" idiom used throughout the domain.
     */
    public UpdateOutcome(boolean modelReArmed) {
        this(modelReArmed, false);
    }
}
