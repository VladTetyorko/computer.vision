package com.drones.vision.perception.application.profile;

import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;

import java.util.List;
import java.util.Objects;

/**
 * What a caller supplies to {@link CvProfileService#create}/{@link CvProfileService#update} — every
 * {@link CvProfile} field the wire contract's {@code POST}/{@code PUT /api/cv/profiles} accepts
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1/&sect;5.2), minus the fields the service itself
 * owns: {@code id}, {@code builtIn} (always {@code false} for anything a caller can create), {@code
 * groupId} (a separate, explicit parameter — see {@link CvProfileService#create}'s own javadoc for
 * why it is not derived from the acting scope), and the two timestamps.
 *
 * <p><b>A patch, mirroring {@link CvProfile}</b> (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7,
 * wave W7, decision E22): every knob below except {@link #intent()} is nullable, and {@code null}
 * means "leave this knob unset on the created/updated profile" — {@link CvProfileService#create}/
 * {@link CvProfileService#update} copy these fields onto {@link CvProfile} verbatim, unresolved;
 * resolving an {@link #intent()} into an actual seeded value is {@link CvProfileResolver}'s job at
 * fold time, never this record's or the service's.
 *
 * @param name                human-readable name; must not be blank
 * @param description         human-readable description; must not be {@code null}, may be blank
 * @param model               the CV model this profile runs, or {@code null} to leave unset
 * @param confidenceThreshold minimum confidence to keep a detection, range [0,1] when set, or {@code
 *                            null} to leave unset
 * @param inferenceFps        target inference sample rate; must be positive when set, or {@code null}
 *                            to leave unset
 * @param labelFilter         labels to keep; empty means all labels; {@code null} to leave unset;
 *                            defensively copied when non-{@code null}
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them; {@code
 *                            null} to leave unset; defensively copied when non-{@code null}
 * @param detectionEnabled    whether a stream started from this profile runs detection at all, or
 *                            {@code null} to leave unset
 * @param tracking            per-knob tracking overrides, or {@code null} to leave the whole group
 *                            unset
 * @param eventRule           debounce rule a stream started from this profile uses, or {@code null}
 *                            to leave unset; start-time only
 * @param intent              the operator's "what am I looking for" pick, or {@code null} for none
 */
public record CvProfileSpec(String name, String description, ModelRef model, Double confidenceThreshold,
                             Integer inferenceFps, List<String> labelFilter, List<String> labelDenyFilter,
                             Boolean detectionEnabled, TrackingKnobPatch tracking, EventRuleConfig eventRule,
                             Intent intent) {

    public CvProfileSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CvProfileSpec name must not be blank");
        }
        Objects.requireNonNull(description, "description must not be null");
        if (confidenceThreshold != null
                && (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0)) {
            throw new IllegalArgumentException(
                    "CvProfileSpec confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (inferenceFps != null && inferenceFps <= 0) {
            throw new IllegalArgumentException("CvProfileSpec inferenceFps must be positive: " + inferenceFps);
        }
        if (intent == Intent.CUSTOM && (labelFilter == null || labelFilter.isEmpty())) {
            throw new IllegalArgumentException("CvProfileSpec labelFilter must not be empty when intent is CUSTOM");
        }
        labelFilter = labelFilter == null ? null : List.copyOf(labelFilter);
        labelDenyFilter = labelDenyFilter == null ? null : List.copyOf(labelDenyFilter);
    }
}
