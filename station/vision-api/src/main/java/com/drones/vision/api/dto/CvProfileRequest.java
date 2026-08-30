package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CvProfileSpec;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.ModelRef;

/**
 * Body for {@code POST /api/cv/profiles} (create) and {@code PUT /api/cv/profiles/{id}} (update) —
 * one shared request shape for both verbs (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1/&sect;5.2's
 * frozen wire contract), this codebase's own precedent ({@code GeofenceZoneRequest}). Deliberately
 * excludes {@code id}/{@code builtIn}/{@code createdAt}/{@code updatedAt} (server-assigned),
 * {@code groupId} (never sent — the owning group comes from the caller's own scope, see {@code
 * CvProfileController}), and {@code eventRule} ({@link CvProfileEventRuleResponse}'s own javadoc:
 * start-time only, never accepted on an update).
 *
 * @param name                human-readable name; must not be blank
 * @param description         human-readable description; must not be {@code null}, may be blank
 * @param model               the CV model id this profile should run — plain string, not a
 *                            versioned {@link ModelRef}; see {@link #toSpec()} for how a version is
 *                            synthesized
 * @param confidenceThreshold minimum confidence to keep a detection, [0,1]
 * @param inferenceFps        target inference sample rate; must be positive
 * @param labelFilter         labels to keep; empty means all labels
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them
 * @param detectionEnabled    whether a stream started from this profile runs detection at all
 * @param tracking            tracking configuration a stream started from this profile uses
 */
public record CvProfileRequest(String name, String description, String model, double confidenceThreshold,
                                int inferenceFps, java.util.List<String> labelFilter,
                                java.util.List<String> labelDenyFilter, boolean detectionEnabled,
                                CvProfileTrackingResponse tracking) {

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
     * Maps this request to an application-layer {@link CvProfileSpec}, defaulting {@code eventRule}
     * to {@link EventRuleConfig#defaults()} — this wire shape never carries one, and the domain
     * requires a non-null value for both create and update.
     *
     * @return the spec {@link com.drones.vision.perception.application.profile.CvProfileService#create}/
     *         {@link com.drones.vision.perception.application.profile.CvProfileService#update} accept
     * @throws IllegalArgumentException if {@code model} is blank, {@code tracking.mode} is unknown,
     *                                  or any field fails {@link CvProfileSpec}'s own validation
     *                                  (&rarr; 400)
     */
    public CvProfileSpec toSpec() {
        return new CvProfileSpec(name, description, new ModelRef(model, DEFAULT_MODEL_VERSION),
                confidenceThreshold, inferenceFps, labelFilter, labelDenyFilter, detectionEnabled,
                tracking.toTrackingConfig(), EventRuleConfig.defaults());
    }
}
