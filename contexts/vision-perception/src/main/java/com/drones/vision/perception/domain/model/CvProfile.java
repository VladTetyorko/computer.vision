package com.drones.vision.perception.domain.model;

import com.drones.vision.kernel.GroupId;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A named, persisted CV configuration — organization/category/asset can be bound to one via a
 * {@link CvProfileBinding}, and the resolver folds asset &rarr; category &rarr; organization
 * &rarr; platform down to the one {@link PipelineConfig} a stream starts with
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.1). Lands UX-DESIGN §4's "presets should be named domain
 * objects, stored and assignable to many devices" — before this, every CV knob lived only in a
 * running {@code StreamPipeline} or one browser's {@code localStorage}.
 *
 * <p>{@link #builtIn()} profiles are the four seeded by the migration ({@code people-vehicles},
 * {@code wide-search}, {@code military-vehicles}, {@code video-only}, docs/plans/active/CV-SETTINGS-PLAN.md
 * §3.4) — not editable in place, only forkable into a new, group-owned profile. {@link #groupId()}
 * is {@code null} <b>if and only if</b> {@link #builtIn()} is {@code true}: a built-in is a global
 * template visible to every group, never owned by one; every other profile belongs to exactly one
 * group (the "group-filtered" half of {@code GET /api/cv/profiles}, docs/plans/active/CV-SETTINGS-PLAN.md
 * §5.2). Forking a built-in produces a new, non-built-in profile with a real {@code groupId} — it
 * never mutates the built-in or leaves it with an owner.
 *
 * <p>{@code labelFilter}/{@code labelDenyFilter} are {@link List}, not {@link Set} — unlike {@link
 * PipelineConfig}'s corresponding fields, order here is the order an operator typed/arranged the
 * labels in, worth preserving for display. {@link #toPipelineConfig(PipelineConfig)} folds both
 * down to sets, matching {@link PipelineConfig}'s own containment-only semantics.
 *
 * <p>{@code eventRule} is per-profile, start-time only (docs/plans/active/CV-SETTINGS-PLAN.md §5.1/§8 Q10) —
 * the debounce engine is built once at {@code StreamService#start} (CV-CONTROL §A) and never
 * re-armed by a live PATCH, so a profile's event rule only takes effect the next time a stream
 * bound to it is started.
 *
 * @param id                   typed profile identity
 * @param name                 human-readable name; must not be blank
 * @param description          human-readable description; must not be {@code null}, may be blank
 * @param builtIn              {@code true} for one of the four migration-seeded, non-editable
 *                             templates; {@code false} for a group-owned profile
 * @param groupId              the owning group, or {@code null} exactly when {@link #builtIn()} is
 *                             {@code true} (a global template has no owner)
 * @param model                the CV model this profile runs
 * @param confidenceThreshold  minimum confidence to keep a detection, range [0,1] — same range as
 *                             {@link PipelineConfig#confidenceThreshold()}
 * @param inferenceFps         target inference sample rate; must be positive — same constraint as
 *                             {@link PipelineConfig#inferenceFps()}
 * @param labelFilter          labels to keep; empty means all labels; defensively copied, order
 *                             preserved
 * @param labelDenyFilter      labels to drop even when {@code labelFilter} would keep them; empty
 *                             means deny nothing; defensively copied, order preserved
 * @param detectionEnabled     whether a stream started from this profile runs detection at all
 * @param tracking             tracking configuration a stream started from this profile uses
 * @param eventRule            debounce rule a stream started from this profile uses; start-time
 *                             only, never re-applied by a live PATCH
 * @param createdAt            when this profile was created; never changes afterward
 * @param updatedAt            when this profile was last edited; must not be before {@code createdAt}
 */
public record CvProfile(CvProfileId id, String name, String description, boolean builtIn, GroupId groupId,
                         ModelRef model, double confidenceThreshold, int inferenceFps,
                         List<String> labelFilter, List<String> labelDenyFilter, boolean detectionEnabled,
                         TrackingConfig tracking, EventRuleConfig eventRule, Instant createdAt,
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
        if (model == null) {
            throw new IllegalArgumentException("CvProfile model must not be null");
        }
        if (Double.isNaN(confidenceThreshold) || confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "CvProfile confidenceThreshold must be within [0,1]: " + confidenceThreshold);
        }
        if (inferenceFps <= 0) {
            throw new IllegalArgumentException("CvProfile inferenceFps must be positive: " + inferenceFps);
        }
        if (labelFilter == null) {
            throw new IllegalArgumentException("CvProfile labelFilter must not be null");
        }
        if (labelDenyFilter == null) {
            throw new IllegalArgumentException("CvProfile labelDenyFilter must not be null");
        }
        if (tracking == null) {
            throw new IllegalArgumentException("CvProfile tracking must not be null");
        }
        if (eventRule == null) {
            throw new IllegalArgumentException("CvProfile eventRule must not be null");
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
        labelFilter = List.copyOf(labelFilter);
        labelDenyFilter = List.copyOf(labelDenyFilter);
    }

    /**
     * Folds this profile over {@code defaults}, producing the one {@link PipelineConfig} a stream
     * started from this profile begins with (docs/plans/active/CV-SETTINGS-PLAN.md §3.1 rule 1: resolution
     * happens once, at {@code start}; nothing here re-resolves mid-flight).
     *
     * <p>Every {@link PipelineConfig} field comes from this profile <b>except</b> {@link
     * PipelineConfig#maxInFlightInferences()} and {@link PipelineConfig#trace()}, which always come
     * from {@code defaults} — neither is a profile concern: {@code maxInFlightInferences} is host
     * capacity (docs/plans/active/CV-SETTINGS-PLAN.md §5.1: "{@code maxInFlightInferences} is
     * deliberately not a profile field"), and {@code trace} is per-session inspector demand
     * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4) — a persisted profile has no notion of
     * "an inspector happens to be open right now". Concretely:
     * <ul>
     *   <li>{@code model}, {@code confidenceThreshold}, {@code inferenceFps}, {@code
     *       detectionEnabled}, {@code tracking}, {@code eventRule} — this profile's own values.</li>
     *   <li>{@code labelFilter}, {@code labelDenyFilter} — this profile's lists, converted to sets
     *       (order is a display concern this profile keeps; {@link PipelineConfig} only tests
     *       membership).</li>
     *   <li>{@code maxInFlightInferences}, {@code trace} — always {@code defaults.maxInFlightInferences()}/
     *       {@code defaults.trace()}.</li>
     * </ul>
     * A profile whose fields exactly mirror {@link PipelineConfig#defaults()}'s own components
     * therefore folds to a byte-identical {@code PipelineConfig.defaults()}.
     *
     * @param defaults the platform defaults to fold over — supplies {@code maxInFlightInferences}
     *                 and {@code trace}
     * @return the resolved {@code PipelineConfig}
     * @throws IllegalArgumentException if {@code defaults} is {@code null}
     */
    public PipelineConfig toPipelineConfig(PipelineConfig defaults) {
        if (defaults == null) {
            throw new IllegalArgumentException("CvProfile toPipelineConfig defaults must not be null");
        }
        return new PipelineConfig(model, confidenceThreshold, inferenceFps, defaults.maxInFlightInferences(),
                Set.copyOf(labelFilter), eventRule, detectionEnabled, tracking, Set.copyOf(labelDenyFilter),
                defaults.trace());
    }
}
