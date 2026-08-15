package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.User;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A managed user on the wire (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — the element type of {@code GET
 * /api/users} and the body of {@code POST /api/users}/{@code POST /api/users/{id}/enabled}.
 *
 * <p>Distinct from {@link MeResponse} (the authenticated-self view, which resolves group names and
 * carries {@code authEnabled}): this is the admin/manager roster view, id-oriented, with no group
 * name resolution — the org-settings UI already holds the groups list to resolve names against.
 * Never carries the password hash. {@code topRole} is omitted when the user has no memberships.
 *
 * @param userId      the user's id, as a canonical UUID string
 * @param username    login handle
 * @param displayName human-readable name
 * @param email       contact address
 * @param enabled     whether the account may authenticate
 * @param memberships the user's group memberships (group id + role); may be empty
 * @param topRole     the highest role held, or absent when the user has none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserResponse(String userId, String username, String displayName, String email, boolean enabled,
                           List<MembershipView> memberships, String topRole) {

    /**
     * One membership on the wire — group id plus role, no resolved name.
     *
     * @param groupId the group id, as a canonical UUID string
     * @param role    the role held (the enum name)
     */
    public record MembershipView(String groupId, String role) {
    }

    /**
     * Maps a domain {@link User} to its wire representation.
     *
     * @param user the user to map
     * @return the response body for {@code user}
     */
    public static UserResponse from(User user) {
        List<MembershipView> memberships = user.memberships().stream()
                .map(UserResponse::toMembership)
                .toList();
        String topRole = user.topRole().map(Enum::name).orElse(null);
        return new UserResponse(user.id().value().toString(), user.username(), user.displayName(),
                user.email(), user.enabled(), memberships, topRole);
    }

    private static MembershipView toMembership(Membership membership) {
        return new MembershipView(membership.groupId().value().toString(), membership.role().name());
    }
}
