package com.drones.vision.perception.domain.model;

/**
 * Which kind of thing a {@link CvProfileBinding} attaches a {@link CvProfile} to
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.1).
 *
 * <p>Ordered here from least to most specific — {@link CvProfileBinding} resolution
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.1's hierarchy: platform &lt; organization &lt; category &lt; asset
 * &lt; session) is an application-layer concern (the resolver, wave W2), not this enum's; this type only
 * names the three bindable scopes.
 */
public enum BindingScope {
    /** Bound to a {@code GroupId} — the deployment-wide fallback below category/asset. */
    ORGANIZATION,
    /** Bound to a {@code CategoryId} — the "camera kind" axis (docs/plans/active/CV-SETTINGS-PLAN.md §3.1 rule 4:
     * reuses the existing data-driven category slug rather than a new device-type taxonomy). */
    CATEGORY,
    /** Bound to an {@code AssetId} — the most specific scope, overriding category and organization. */
    ASSET
}
