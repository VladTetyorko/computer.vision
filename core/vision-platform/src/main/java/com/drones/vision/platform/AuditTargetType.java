package com.drones.vision.platform;

/**
 * The kind of thing an {@link AuditEntry} is about.
 *
 * <p>Kept as an enum plus an opaque id string rather than a union of typed ids, so adding
 * auditable kinds later (categories, users, trained models) does not ripple through the trail's
 * storage or its API.
 */
public enum AuditTargetType {

    /** A user-facing asset — "my drone". */
    ASSET,

    /** A single source underneath an asset. */
    DEVICE,

    /** A training dataset accumulating labeled samples (docs/plans/done/CV-TRAINING-PLAN.md §1/§2). */
    DATASET,

    /** A trained CV model version in the registry (docs/plans/done/CV-TRAINING-PLAN.md §8, Phase 2). */
    MODEL,

    /** A login-capable account (docs/plans/active/AUTH-ROLES-PLAN.md D15, wave B2) — created, enabled/
     * disabled, password changed/reset, or authenticated against (successfully or not). */
    USER,

    /** An org-chart node users hold memberships in (docs/plans/active/AUTH-ROLES-PLAN.md D15, wave B2). */
    GROUP,

    /** A pilot&rarr;asset link — grant, revoke, or seat change between {@code PILOT}/{@code CREW}
     * (docs/plans/active/AUTH-ROLES-PLAN.md D15, wave B2). */
    ASSIGNMENT,

    /** A vehicle's persisted identity — sysid, key, hardware uid, radio bind
     * (docs/plans/active/LINK-PAIRING-PLAN.md §3.5). */
    PAIRING
}
