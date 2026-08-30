package com.drones.vision.perception.application.profile;

/**
 * Which layer of the asset &rarr; category &rarr; organization &rarr; platform fold
 * (docs/plans/active/CV-SETTINGS-PLAN.md &sect;3.1) actually supplied an {@link EffectiveProfile}'s
 * configuration — the {@code source} field of the wire contract's {@code GET
 * /api/cv/profiles/effective} response (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2).
 */
public enum ProfileSource {

    /** A {@code CvProfileBinding} bound directly to the asset. */
    ASSET,

    /** No asset binding; a {@code CvProfileBinding} bound to the asset's category. */
    CATEGORY,

    /** No asset or category binding; a {@code CvProfileBinding} bound to the asset's organization. */
    ORGANIZATION,

    /** No binding at any level; the caller-supplied platform default applies unchanged. */
    PLATFORM
}
