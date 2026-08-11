package com.drones.vision.domain.model;

/**
 * An org-chart node a {@link User} can hold a {@link Membership} in.
 *
 * <p>Groups form a tree: {@code parentGroupId} points at the enclosing group, and {@code null}
 * marks a root group (a group with no parent). Slice 1 (docs/plans/done/U-AUTH-PLAN.md) only stores and
 * lists groups — subtree visibility scoping is a later slice.
 *
 * @param id            typed group identity
 * @param name          human-readable name; must not be blank
 * @param parentGroupId the enclosing group, or {@code null} if this is a root group
 */
public record Group(GroupId id, String name, GroupId parentGroupId) {

    public Group {
        if (id == null) {
            throw new IllegalArgumentException("Group id must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Group name must not be blank");
        }
    }
}
