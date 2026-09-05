package com.drones.vision.identity.domain.model;

import com.drones.vision.kernel.GroupId;

import java.io.Serializable;

/**
 * A {@link User}'s {@link Role} within one {@link Group}.
 *
 * <p>A user may hold several memberships (one per group they belong to, potentially with
 * different roles in each); memberships ride on the {@link User} aggregate and are saved whole
 * with it — there is no separate membership repository port.
 *
 * <p>{@link Serializable} (docs/plans/active/AUTH-ROLES-PLAN.md B5-fix): {@code User#memberships()}
 * is reachable from the {@code VisionUserDetails} principal (station/vision-app) that Spring
 * Session JDBC java-serializes into {@code spring_session_attributes}. {@link Role} is an enum
 * and already {@link Serializable} for free; {@link GroupId} is made {@link Serializable} for the
 * same reason.
 *
 * @param groupId the group this membership grants a role in; must not be {@code null}
 * @param role    the role held in that group; must not be {@code null}
 */
public record Membership(GroupId groupId, Role role) implements Serializable {

    public Membership {
        if (groupId == null) {
            throw new IllegalArgumentException("Membership groupId must not be null");
        }
        if (role == null) {
            throw new IllegalArgumentException("Membership role must not be null");
        }
    }
}
