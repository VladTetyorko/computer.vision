package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.GroupId;

import java.util.Objects;

/**
 * Everything needed to bootstrap the very first {@link User} on a fresh station
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2) — the {@code POST /api/auth/bootstrap} body,
 * resolved by the caller onto a concrete {@link GroupId} (creating the fixed root group first, if
 * none exists) before reaching {@link UserService#createFirstAdmin(FirstAdminSpec)}.
 *
 * <p>Deliberately not {@link UserSpec}: bootstrap always grants exactly one
 * {@link com.drones.vision.identity.domain.model.Role#ADMIN} membership and always creates an
 * enabled account that does not require an immediate forced password change (the operator chose
 * this password themselves, at setup — nobody handed it to them) — accepting a
 * {@code memberships}/{@code enabled} pair that {@link UserService#createFirstAdmin} would always
 * override anyway is worse than a dedicated, honest shape.
 *
 * @param username    desired login handle; shape-validated by {@link User} when the aggregate is built
 * @param displayName human-readable name; shape-validated by {@link User}
 * @param email       contact address; shape-validated by {@link User}
 * @param rawPassword the plaintext password to hash; must not be blank
 * @param groupId     the group the new administrator's {@code ADMIN} membership is granted in
 */
public record FirstAdminSpec(String username, String displayName, String email, String rawPassword,
                              GroupId groupId) {

    public FirstAdminSpec {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("FirstAdminSpec rawPassword must not be blank");
        }
        Objects.requireNonNull(groupId, "FirstAdminSpec groupId must not be null");
    }
}
