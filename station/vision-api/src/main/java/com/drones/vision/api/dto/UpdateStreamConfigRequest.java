package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.IntentPolicy;
import com.drones.vision.perception.application.profile.IntentPolicyResolver;
import com.drones.vision.perception.application.profile.ProfileSource;
import com.drones.vision.perception.application.stream.PipelineConfigPatch;
import com.drones.vision.perception.domain.model.Intent;

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
 * (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2) is the one field added since that freeze, mirroring
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
 * @param intent              the operator's "what am I looking for" pick
 *                            (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.0, Java only
 *                            — no web surface consumes this field yet), or {@code null} to leave
 *                            the fields below exactly as sent. When non-{@code null}, {@link
 *                            #toPatch()} asks {@link IntentPolicyResolver} for the resolved {@link
 *                            IntentPolicy} and uses it to seed {@code model}/{@code labelFilter}
 *                            only when this request left those fields absent — an explicit {@code
 *                            model} or {@code labelFilter} (even an explicit empty one, which
 *                            already means "keep all labels" under this PATCH's own null-means-
 *                            unchanged contract) always wins over the intent's own choice
 */
public record UpdateStreamConfigRequest(Double confidenceThreshold, Integer inferenceFps, List<String> labelFilter,
                                         List<String> labelDenyFilter, Boolean detectionEnabled, String model,
                                         TrackingConfigRequest tracking, Intent intent) {

    /** No body at all: a patch that changes nothing. */
    public static final UpdateStreamConfigRequest EMPTY =
            new UpdateStreamConfigRequest(null, null, null, null, null, null, null, null);

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
        this(confidenceThreshold, inferenceFps, labelFilter, null, detectionEnabled, model, tracking, null);
    }

    /**
     * Maps this request to the application-level patch (docs/plans/done/TRACKING-PLAN.md §4.D), field for
     * field: an absent JSON field is a {@code null} the application layer reads as "leave this knob
     * unchanged", and an absent {@code tracking} object leaves tracking entirely alone.
     *
     * <p>Nothing is merged here on purpose. The running configuration is the application layer's
     * state; this edge cannot read it back, and a controller that reconstructed it from a read model
     * could only ever restore the fields that happen to be observable — which is exactly how the
     * cadence knobs used to get reset by an unrelated patch. {@link #intent} is the one exception,
     * and only in appearance: resolving it needs no read of the running stream, only this request's
     * own fields, so folding it in here does not compromise that reasoning.
     *
     * <p><b>Note on the "was this explicit" test:</b> {@link #intent}'s seed only applies when
     * {@code model}/{@code labelFilter} are {@code null} here — a plain non-null check, not {@link
     * CvProfileRequest#toSpec()}'s blank-string/empty-list check. That is deliberate, not an
     * inconsistency to "fix" later: {@link CvProfileRequest}'s fields are always-present or blank
     * means the same as absent, but this DTO is already a true partial patch where {@code null}
     * itself means "not sent" and a non-{@code null} (even empty) list is a real, explicit value —
     * see this record's own javadoc on {@code labelFilter}.
     *
     * @return the partial patch to apply
     * @throws IllegalArgumentException if a {@code tracking} object is present but invalid (→400),
     *                                   or if {@code intent} is {@link Intent#CUSTOM} and {@code
     *                                   labelFilter} is absent
     */
    public PipelineConfigPatch toPatch() {
        IntentPolicy resolved = intent == null ? null : IntentPolicyResolver.resolve(intent, labelFilter);
        String resolvedModel = model != null ? model : resolved != null ? resolved.model().id() : null;
        List<String> resolvedLabelFilter = labelFilter != null ? labelFilter
                : resolved != null ? List.copyOf(resolved.classSet()) : null;
        Set<String> filter = resolvedLabelFilter == null ? null : Set.copyOf(resolvedLabelFilter);
        Set<String> denyFilter = labelDenyFilter == null ? null : Set.copyOf(labelDenyFilter);
        return new PipelineConfigPatch(confidenceThreshold, inferenceFps, filter, detectionEnabled, resolvedModel,
                tracking == null ? null : tracking.toPatch(), denyFilter);
    }

    /**
     * Which of this request's own knobs {@link #toPatch()} actually seeded from {@link #intent}
     * rather than taking verbatim (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.0) —
     * mirrors {@link CvProfileRequest#fieldSources()} field-for-field, but reuses {@link
     * CvProfileResponse.Sources} directly rather than a second, near-identical nested type, since
     * both DTOs report provenance for exactly the same two knobs.
     *
     * <p>The "was this explicit" test here is plain non-null, not blank/empty — see {@link
     * #toPatch()}'s own note on why that is correct for this DTO and not an inconsistency with
     * {@link CvProfileRequest#fieldSources()}'s blank/empty test.
     *
     * @return {@link CvProfileResponse.Sources#none()} when {@link #intent} is {@code null}
     *         (intent resolution was skipped entirely, so nothing could have been seeded);
     *         otherwise a {@link CvProfileResponse.Sources} reporting {@link ProfileSource#INTENT}
     *         for {@code model} iff this request's own {@code model} was absent, and for {@code
     *         labelFilter} iff this request's own {@code labelFilter} was absent
     */
    public CvProfileResponse.Sources fieldSources() {
        if (intent == null) {
            return CvProfileResponse.Sources.none();
        }
        ProfileSource modelSource = model == null ? ProfileSource.INTENT : null;
        ProfileSource labelFilterSource = labelFilter == null ? ProfileSource.INTENT : null;
        return new CvProfileResponse.Sources(modelSource, labelFilterSource);
    }
}
