package com.drones.vision.identity.domain.model;

/**
 * A user's privilege level within one {@link Group}, held via a {@link Membership}.
 *
 * <p><strong>Declaration order is meaningful.</strong> Constants are declared
 * least-to-most privileged, so the natural enum ordinal ordering (comparable via
 * {@link Enum#compareTo(Enum)}) is "more privileged." {@link User#topRole()} relies on this
 * ordering to pick the highest role across a user's memberships — reordering these constants
 * would silently change what "top" means.
 *
 * <p><strong>{@link #VIEWER} (docs/plans/active/AUTH-ROLES-PLAN.md §3.2, wave B1) is a deliberate,
 * narrow exception, prepended rather than inserted or appended.</strong> A prepend does not reorder
 * {@code PILOT < MANAGER < ADMIN}, so {@link User#topRole()} still picks the same winner for every
 * user that predates this constant — nothing outranks {@code ADMIN}, and no ordinal is persisted or
 * compared against a literal anywhere in this codebase (roles persist by name). Read the ordering
 * that ordinal gives as <strong>authority</strong>, never visibility: {@code VIEWER} sits at the
 * bottom of what a caller may <em>do</em> — a {@code VIEWER} account may never command anything,
 * anywhere — while being deliberately given, once
 * docs/plans/active/AUTH-ROLES-PLAN.md's wave B6 lands, one of the <em>widest</em> visibility scopes
 * of the four roles (a group-wide read, the same shape a {@code MANAGER}'s scope has). That
 * inversion — least authority, wide visibility — is the whole point of a "screen on a wall" account
 * and is exactly why {@link com.drones.vision.platform.VisibilityScope}'s own javadoc insists
 * visibility and authority are different questions; do not read {@code VIEWER}'s low ordinal as "sees
 * least."
 */
public enum Role {

    /** May never command anything, anywhere — an always-on display, not a person with a stick. Holds
     * no {@link com.drones.vision.platform.Capability}; see this enum's own javadoc for why its
     * ordinal position (bottom, i.e. least <em>authority</em>) does not imply narrow visibility. */
    VIEWER,

    /** Flies/operates assigned assets. */
    PILOT,

    /** Manages a group's assets and pilots. */
    MANAGER,

    /** Full administrative privilege; the most-privileged role. */
    ADMIN
}
