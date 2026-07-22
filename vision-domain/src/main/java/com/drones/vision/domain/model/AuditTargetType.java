package com.drones.vision.domain.model;

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
    DEVICE
}
