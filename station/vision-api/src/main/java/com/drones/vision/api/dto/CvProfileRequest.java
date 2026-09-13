package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CvProfileSpec;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;
import com.drones.vision.perception.domain.model.TrackingKnobPatch;

import java.util.List;

/**
 * Body for {@code POST /api/cv/profiles} (create) and {@code PUT /api/cv/profiles/{id}} (update) —
 * one shared request shape for both verbs (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1/&sect;5.2's
 * frozen wire contract), this codebase's own precedent ({@code GeofenceZoneRequest}). Deliberately
 * excludes {@code id}/{@code builtIn}/{@code createdAt}/{@code updatedAt} (server-assigned),
 * {@code groupId} (never sent — the owning group comes from the caller's own scope, see {@code
 * CvProfileController}), and {@code eventRule} ({@link CvProfileEventRuleResponse}'s own javadoc:
 * start-time only, never accepted on an update or a create — {@link #toSpec()} always passes
 * {@code null} for it).
 *
 * <h2>Wave W7.3 — a request is a patch too (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7,
 * decision E22)</h2>
 * Every knob below except {@link #intent()} is nullable, mirroring {@link CvProfileSpec}/{@link
 * com.drones.vision.perception.domain.model.CvProfile} field-for-field: {@code null} (a blank {@code
 * model}, an absent {@code tracking} object) leaves that knob unset on the created/updated profile —
 * {@link #toSpec()} is a straight, unresolved pass-through onto {@link CvProfileSpec}, never
 * consulting {@link #intent()} to seed any other field.
 *
 * <p><b>Corrected from the pre-W7.3 shape</b>, which resolved {@link #intent()} into {@code model}/
 * {@code labelFilter}/a synthesized {@code eventRule} at save time via {@code IntentPolicyResolver},
 * and reported which fields it had seeded through a since-deleted {@code fieldSources()} method.
 * Decision E22's whole point is that {@link #intent()} survives on the saved {@link
 * com.drones.vision.perception.domain.model.CvProfile} itself and is resolved at FOLD time by {@code
 * CvProfileResolver} (wave W7.1) — resolving it a second time here, at save time, would both
 * duplicate that logic and bake in a value that could go stale the moment {@code
 * IntentPolicyResolver}'s own policy table changes. {@link CvProfileResponse#from(com.drones.vision.perception.domain.model.CvProfile)}'s
 * {@code Sources} now answers "was this seeded from intent" by reading the saved profile itself, on
 * every read — not by remembering what one particular save request happened to compute.
 *
 * @param name                human-readable name; must not be blank
 * @param description         human-readable description; must not be {@code null}, may be blank
 * @param model               the CV model id this profile should run — plain string, not a
 *                            versioned {@link ModelRef}; see {@link #toSpec()} for how a version is
 *                            synthesized; blank or {@code null} leaves the knob unset
 * @param confidenceThreshold minimum confidence to keep a detection, [0,1], or {@code null} to leave
 *                            unset
 * @param inferenceFps        target inference sample rate; must be positive when set, or {@code
 *                            null} to leave unset
 * @param labelFilter         labels to keep; empty is an explicit "all labels"; {@code null} leaves
 *                            the knob unset
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them; empty is
 *                            an explicit "deny nothing"; {@code null} leaves the knob unset
 * @param detectionEnabled    whether a stream started from this profile runs detection at all, or
 *                            {@code null} to leave unset
 * @param tracking            per-knob tracking overrides, or {@code null} to leave the whole group
 *                            unset
 * @param intent              the operator's "what am I looking for" pick, persisted with the profile
 *                            and resolved later at fold time — {@code null} for none
 */
public record CvProfileRequest(String name, String description, String model, Double confidenceThreshold,
                                Integer inferenceFps, List<String> labelFilter, List<String> labelDenyFilter,
                                Boolean detectionEnabled, CvProfileTrackingResponse tracking, Intent intent) {

    /**
     * Sentinel {@link ModelRef#version()} used when a request only ever names a bare model id (no
     * version travels over this wire, matching {@code StartStreamRequest}/{@code
     * PromoteModelRequest}'s own plain-string model fields) — the same {@code "latest"} sentinel
     * {@code CvWiring#configModelCatalog} already synthesizes for a config-catalog-derived {@link
     * com.drones.vision.learning.domain.model.CvModelRecord#version()}, reused here rather than
     * invented a second time.
     */
    private static final String DEFAULT_MODEL_VERSION = "latest";

    /**
     * Maps this request straight onto an application-layer {@link CvProfileSpec} — every knob
     * unresolved, {@link #intent()} carried through verbatim rather than resolved here (see this
     * record's own javadoc for why that changed in wave W7.3).
     *
     * @return the spec {@link com.drones.vision.perception.application.profile.CvProfileService#create}/
     *         {@link com.drones.vision.perception.application.profile.CvProfileService#update} accept
     * @throws IllegalArgumentException if {@code name} is blank, {@code tracking.mode} is set but
     *                                  unknown, {@code intent} is {@link Intent#CUSTOM} and {@code
     *                                  labelFilter} is empty or unset, or any field fails {@link
     *                                  CvProfileSpec}'s own validation (&rarr; 400)
     */
    public CvProfileSpec toSpec() {
        ModelRef modelRef = model == null || model.isBlank() ? null : new ModelRef(model, DEFAULT_MODEL_VERSION);
        TrackingKnobPatch trackingPatch = tracking == null ? null : tracking.toTrackingKnobPatch();
        return new CvProfileSpec(name, description, modelRef, confidenceThreshold, inferenceFps, labelFilter,
                labelDenyFilter, detectionEnabled, trackingPatch, null, intent);
    }
}
