package com.drones.vision.domain.model;

/**
 * Who an {@link Asset} belongs to: an owning user within a group.
 *
 * <p>Ownership lives on the asset, not the device — devices inherit scope
 * through their asset, so streams/detections/usages all follow a single
 * scope rule (ARCHITECTURE.md §6). Until the identity phase (Phase 6), a
 * constant dev principal (fixed {@code UserId}/{@code GroupId} values, e.g.
 * wrapping {@code new UUID(0, 0)}/{@code new UUID(0, 1)}) owns everything;
 * that constant is applied in the application layer, never hard-coded in
 * the domain.
 *
 * @param ownerId owning user; must not be {@code null}
 * @param groupId owning group; must not be {@code null}
 */
public record Ownership(UserId ownerId, GroupId groupId) {

    public Ownership {
        if (ownerId == null) {
            throw new IllegalArgumentException("Ownership ownerId must not be null");
        }
        if (groupId == null) {
            throw new IllegalArgumentException("Ownership groupId must not be null");
        }
    }
}
