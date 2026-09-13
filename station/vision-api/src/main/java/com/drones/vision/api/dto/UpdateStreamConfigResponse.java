package com.drones.vision.api.dto;

/**
 * Response body for {@code PATCH /api/streams/{streamId}/config} (docs/plans/done/CV-CONTROL-PLAN.md §3's
 * frozen wire contract, extended by docs/plans/done/TRACKING-PLAN.md §4.D).
 *
 * @param streamId     the updated stream's id, canonical UUID string
 * @param modelReArmed {@code true} only when the patch's {@code model} field was present and
 *                     differed from the stream's running model, so the caller knows a brief
 *                     detection gap occurred while the detector swapped — straight from {@code
 *                     UpdateOutcome#modelReArmed()} (vision-application). Every other knob applies
 *                     hot and never re-arms
 * @param trackingChanged {@code true} only when the patch's {@code tracking} object was present
 *                     <b>and</b> produced a different tracking configuration than the running one —
 *                     restating the identical configuration is not a change (a re-issued lock does
 *                     count, since the application layer stamps it with a fresh {@code lockSeq}).
 *                     Straight from {@code UpdateOutcome#trackingChanged()}. <b>Never implies a
 *                     re-arm</b>: tracking is a hot knob throughout
 * @param sources      per-knob provenance for the same PATCH request's {@code intent}
 *                     (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.0) — straight from
 *                     {@link UpdateStreamConfigRequest#fieldSources()}; {@link
 *                     CvProfileResponse.Sources#none()} when the request carried no {@code intent}
 *                     at all
 */
public record UpdateStreamConfigResponse(String streamId, boolean modelReArmed, boolean trackingChanged,
                                          CvProfileResponse.Sources sources) {

    /**
     * The canonical constructor before docs/plans/done/TRACKING-PLAN.md wave T6 added {@code trackingChanged},
     * kept as a convenience constructor defaulting it to {@code false} — the same N-1-arg idiom used
     * throughout this codebase. Now also defaults {@code sources} to the explicit {@link
     * CvProfileResponse.Sources#none()} value, never a bare {@code null} (CLAUDE.md rule 10).
     *
     * @param streamId     the updated stream's id
     * @param modelReArmed whether the model swap re-armed the detector
     */
    public UpdateStreamConfigResponse(String streamId, boolean modelReArmed) {
        this(streamId, modelReArmed, false, CvProfileResponse.Sources.none());
    }
}
