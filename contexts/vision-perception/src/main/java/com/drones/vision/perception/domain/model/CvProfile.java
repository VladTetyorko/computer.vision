package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.GroupId;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A named, persisted CV configuration — organization/category/asset can be bound to one via a
 * {@link CvProfileBinding}, and the resolver folds asset &rarr; category &rarr; organization &rarr;
 * platform down to the one {@link PipelineConfig} a stream starts with (docs/plans/active/
 * CV-SETTINGS-PLAN.md §3.1). Lands UX-DESIGN §4's "presets should be named domain objects, stored and
 * assignable to many devices" — before this, every CV knob lived only in a running {@code
 * StreamPipeline} or one browser's {@code localStorage}.
 *
 * <p>{@link #builtIn()} profiles are the four seeded by the migration ({@code people-vehicles},
 * {@code wide-search}, {@code military-vehicles}, {@code video-only}, docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.4) — not editable in place, only forkable into a new, group-owned profile. {@link #groupId()}
 * is {@code null} <b>if and only if</b> {@link #builtIn()} is {@code true}: a built-in is a global
 * template visible to every group, never owned by one; every other profile belongs to exactly one
 * group (the "group-filtered" half of {@code GET /api/cv/profiles}, docs/plans/active/CV-SETTINGS-PLAN.md
 * §5.2). Forking a built-in produces a new, non-built-in profile with a real {@code groupId} — it
 * never mutates the built-in or leaves it with an owner. Every built-in ships with all eight knobs
 * below set (docs/plans/active/CV-ORCHESTRATION-PLAN.md §6's "every built-in row folds byte-identical
 * to before" acceptance line) — nothing enforces that at construction time, since a partially
 * specified built-in is not otherwise invalid, but {@code V29__cv_profiles.sql}/{@code
 * V36__cv_profile_patch.sql} never seed one.
 *
 * <h2>A profile is a patch (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8 decision E22, wave W7)</h2>
 * Every knob below except {@link #intent()} is nullable, and {@code null} means <b>inherit this
 * knob from the tier below</b> — the same null-means-unchanged idiom {@link
 * com.drones.vision.perception.application.stream.PipelineConfigPatch}/{@link
 * com.drones.vision.perception.application.stream.TrackingConfigPatch} already document for a live
 * stream's PATCH path (CLAUDE.md rule 10: "unset," never "off," never a second boolean flag). Before
 * this wave every {@link CvProfile} fully specified every field, so an organization profile setting
 * one knob could not compose under a more specific asset profile — {@link #foldOnto(PipelineConfig)}
 * is what makes composition possible; {@link CvProfileResolver} is what actually walks the
 * organization &rarr; category &rarr; asset chain calling it once per bound tier
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7).
 *
 * <p>{@code labelFilter}/{@code labelDenyFilter} keep their pre-existing "empty means a real,
 * explicit value" convention ({@code PipelineConfigPatch#labelFilter()}'s own precedent) — {@code
 * null} inherits, an empty (non-{@code null}) list is this profile's own explicit "all labels"/"deny
 * nothing." {@link #tracking()} inherits as a whole group when {@code null}, and per-knob within that
 * group otherwise — see {@link TrackingKnobPatch}. {@link #eventRule()} inherits as one unit; it is
 * never split into per-field knobs (start-time only, docs/plans/active/CV-SETTINGS-PLAN.md §5.1/§8
 * Q10 — unchanged by this wave).
 *
 * <p>{@link #intent()} (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W2.6, persisted with the
 * profile since W7 rather than resolved once at save time) is the operator's "what am I looking for"
 * pick — {@code null} means no intent. {@link #foldOnto(PipelineConfig)} consults it only to decide
 * whether {@link Intent#CUSTOM}'s guard applies to {@link #labelFilter()}; turning an intent into an
 * actual seeded {@link PipelineConfig} is {@link CvProfileResolver}'s job (it, not this domain record,
 * can see {@code IntentPolicyResolver} — see this class's own {@link #foldOnto(PipelineConfig)}
 * javadoc for exactly why that split exists).
 *
 * @param id                   typed profile identity
 * @param name                 human-readable name; must not be blank
 * @param description          human-readable description; must not be {@code null}, may be blank
 * @param builtIn              {@code true} for one of the four migration-seeded, non-editable
 *                             templates; {@code false} for a group-owned profile
 * @param groupId              the owning group, or {@code null} exactly when {@link #builtIn()} is
 *                             {@code true} (a global template has no owner)
 * @param model                the CV model this profile runs, or {@code null} to inherit
 * @param confidenceThreshold  minimum confidence to keep a detection, range [0,1] when set, or
 *                             {@code null} to inherit
 * @param inferenceFps         target inference sample rate; must be positive when set, or {@code
 *                             null} to inherit
 * @param labelFilter          labels to keep; empty means all labels; {@code null} to inherit;
 *                             defensively copied when non-{@code null}, order preserved
 * @param labelDenyFilter      labels to drop even when {@code labelFilter} would keep them; empty
 *                             means deny nothing; {@code null} to inherit; defensively copied when
 *                             non-{@code null}, order preserved
 * @param detectionEnabled     whether a stream started from this profile runs detection at all, or
 *                             {@code null} to inherit
 * @param tracking             per-knob tracking overrides, or {@code null} to inherit the whole group
 * @param eventRule            debounce rule a stream started from this profile uses, inherited as one
 *                             unit when {@code null}; start-time only, never re-applied by a live PATCH
 * @param intent               the operator's "what am I looking for" pick, or {@code null} for none
 * @param createdAt            when this profile was created; never changes afterward
 * @param updatedAt            when this profile was last edited; must not be before {@code createdAt}
 */
public record CvProfile(CvProfileId id, String name, String description, boolean builtIn, GroupId groupId,
                         ModelRef model, Double confidenceThreshold, Integer inferenceFps,
                         List<String> labelFilter, List<String> labelDenyFilter, Boolean detectionEnabled,
                         TrackingKnobPatch tracking, EventRuleConfig eventRule, Intent intent, Instant createdAt,
                         Instant updatedAt) {

    public CvProfile {
        if (id == null) {
            throw new IllegalArgumentException("CvProfile id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CvProfile name must not be blank");
        }
        if (description == null) {
            throw new IllegalArgumentException("CvProfile description must not be null");
        }
        if (builtIn && groupId != null) {
            throw new IllegalArgumentException("CvProfile groupId must be null for a built-in profile");
        }
        if (!builtIn && groupId == null) {
            throw new IllegalArgumentException("CvProfile groupId must not be null for a non-built-in profile");
        }
        if (confidenceThreshold != null
                && (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0)) {
            throw new IllegalArgumentException(
                    "CvProfile confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (inferenceFps != null && inferenceFps <= 0) {
            throw new IllegalArgumentException("CvProfile inferenceFps must be positive: " + inferenceFps);
        }
        if (intent == Intent.CUSTOM && (labelFilter == null || labelFilter.isEmpty())) {
            throw new IllegalArgumentException("CvProfile labelFilter must not be empty when intent is CUSTOM");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("CvProfile createdAt must not be null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("CvProfile updatedAt must not be null");
        }
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("CvProfile updatedAt must not be before createdAt");
        }
        labelFilter = labelFilter == null ? null : List.copyOf(labelFilter);
        labelDenyFilter = labelDenyFilter == null ? null : List.copyOf(labelDenyFilter);
    }

    /**
     * Folds this profile's own explicit knobs over {@code below}, leaving every knob this profile
     * leaves {@code null} exactly as {@code below} has it — the per-knob patch {@link
     * CvProfileResolver} calls once per bound tier, organization first (docs/plans/active/
     * CV-ORCHESTRATION-PLAN.md §4.7, wave W7, decision E22), replacing the pre-W7 {@code
     * toPipelineConfig(PipelineConfig)} wholesale fold.
     *
     * <p><b>Intent-agnostic by design:</b> this method never calls {@code IntentPolicyResolver} even
     * though {@link #intent()} may be non-{@code null} — {@code IntentPolicyResolver}/{@code
     * IntentPolicy} live in {@code application.profile}, and this record lives in {@code domain.model};
     * {@code ArchitectureTest}'s domain-purity rule forbids a domain type from depending on an
     * application-layer one. E22's own text asks for {@code foldOnto} to compute {@code seed = intent
     * == null ? below : below patched with IntentPolicyResolver.resolve(...)} internally; this wave
     * instead has {@link CvProfileResolver} — which already lives beside {@code IntentPolicyResolver}
     * and legally may call it — compute that same intent-seeded {@code below} itself before invoking
     * this method for each tier, so the *sequence* of operations E22 asks for is unchanged, only which
     * class performs the intent-resolution half of it. Every other clause of point 1 (per-knob
     * inheritance, the {@code CUSTOM} guard, {@code maxInFlightInferences}/{@code trace} always from
     * {@code below}) is implemented exactly as specified, here.
     *
     * @param below the configuration this profile's absent knobs fall back to — a lower tier's already
     *              -folded result, an intent-seeded seed, or the platform default; supplies {@code
     *              maxInFlightInferences} and {@code trace} unconditionally, same as the pre-W7 fold
     * @return the folded {@code PipelineConfig}
     * @throws IllegalArgumentException if {@code below} is {@code null}
     */
    public PipelineConfig foldOnto(PipelineConfig below) {
        if (below == null) {
            throw new IllegalArgumentException("CvProfile foldOnto below must not be null");
        }
        return new PipelineConfig(
                model == null ? below.model() : model,
                confidenceThreshold == null ? below.confidenceThreshold() : confidenceThreshold,
                inferenceFps == null ? below.inferenceFps() : inferenceFps,
                below.maxInFlightInferences(),
                labelFilter == null ? below.labelFilter() : Set.copyOf(labelFilter),
                eventRule == null ? below.eventRule() : eventRule,
                detectionEnabled == null ? below.detectionEnabled() : detectionEnabled,
                tracking == null ? below.tracking() : tracking.foldOnto(below.tracking()),
                labelDenyFilter == null ? below.labelDenyFilter() : Set.copyOf(labelDenyFilter),
                below.trace());
    }
}
