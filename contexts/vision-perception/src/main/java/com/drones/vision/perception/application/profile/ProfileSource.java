package com.drones.vision.perception.application.profile;

/**
 * Which layer of the asset &rarr; category &rarr; organization &rarr; platform fold
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1) actually supplied an {@link EffectiveProfile}'s
 * configuration — the {@code source} field of the wire contract's {@code GET
 * /api/cv/profiles/effective} response (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2).
 *
 * <p>{@link #INTENT} (wave W2.8, docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7) is a
 * different kind of member from the other four: those four name which <em>tier</em> of the
 * asset/category/organization/platform binding fold matched as a whole, at resolution time
 * ({@link CvProfileResolver#resolve}); {@link #INTENT} names a single <em>knob</em>'s provenance
 * at profile <em>creation/update</em> time — {@code station/vision-api}'s {@code
 * CvProfileRequest#fieldSources()} reports it for exactly the fields {@link IntentPolicyResolver}
 * can seed ({@code model}, {@code labelFilter}) when the request left that field blank/empty and
 * named a non-{@code null} {@code intent}. Never returned by {@link CvProfileResolver} itself,
 * which only ever folds complete, already-persisted {@link
 * com.drones.vision.perception.domain.model.CvProfile}s and has no memory of which of their fields
 * were originally intent-seeded — that provenance is not persisted (see {@code
 * CvProfileResponse.Sources}'s own javadoc for why widening the schema to remember it was scoped
 * out of this wave).
 */
public enum ProfileSource {

    /** A {@code CvProfileBinding} bound directly to the asset. */
    ASSET,

    /** No asset binding; a {@code CvProfileBinding} bound to the asset's category. */
    CATEGORY,

    /** No asset or category binding; a {@code CvProfileBinding} bound to the asset's organization. */
    ORGANIZATION,

    /** No binding at any level; the caller-supplied platform default applies unchanged. */
    PLATFORM,

    /** This knob's value was seeded by {@link IntentPolicyResolver} rather than sent explicitly. */
    INTENT
}
