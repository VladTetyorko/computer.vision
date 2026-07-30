package com.drones.vision.domain.model;

/**
 * A user's privilege level within one {@link Group}, held via a {@link Membership}.
 *
 * <p><strong>Declaration order is meaningful.</strong> Constants are declared
 * least-to-most privileged, so the natural enum ordinal ordering (comparable via
 * {@link Enum#compareTo(Enum)}) is "more privileged." {@link User#topRole()} relies on this
 * ordering to pick the highest role across a user's memberships — reordering these constants
 * would silently change what "top" means.
 */
public enum Role {

    /** Flies/operates assigned assets; the least-privileged role. */
    PILOT,

    /** Manages a group's assets and pilots. */
    MANAGER,

    /** Full administrative privilege; the most-privileged role. */
    ADMIN
}
