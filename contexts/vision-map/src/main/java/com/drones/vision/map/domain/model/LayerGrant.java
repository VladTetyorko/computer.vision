package com.drones.vision.map.domain.model;

import java.util.UUID;

/**
 * One explicit access grant on a {@link MapLayer} (docs/plans/done/MAP-REWORK-PLAN.md §2.1) — "give this user
 * or group this level of access", DELTA's "give N participants access to a layer" idea.
 *
 * <p>{@code subjectId} is deliberately a plain {@link UUID} rather than a typed {@code UserId}/
 * {@code GroupId} union, since which typed id it is depends on {@link #subjectType()} — resolving
 * it to the right typed id and checking it against a viewer's identity/memberships is {@code
 * MapAccessPolicy}'s job (vision-application), not this record's.
 *
 * @param subjectType whether {@link #subjectId()} names a user or a group
 * @param subjectId   the granted subject's id (a {@code UserId#value()} or {@code GroupId#value()}
 *                    depending on {@link #subjectType()})
 * @param level       the access level granted
 */
public record LayerGrant(SubjectType subjectType, UUID subjectId, AccessLevel level) {

    public LayerGrant {
        if (subjectType == null) {
            throw new IllegalArgumentException("LayerGrant subjectType must not be null");
        }
        if (subjectId == null) {
            throw new IllegalArgumentException("LayerGrant subjectId must not be null");
        }
        if (level == null) {
            throw new IllegalArgumentException("LayerGrant level must not be null");
        }
    }

    /** What kind of subject a {@link LayerGrant} names. */
    public enum SubjectType {
        /** {@link LayerGrant#subjectId()} is a {@code UserId#value()}. */
        USER,
        /** {@link LayerGrant#subjectId()} is a {@code GroupId#value()}. */
        GROUP
    }
}
