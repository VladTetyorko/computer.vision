package com.drones.vision.api.dto;

import com.drones.vision.identity.application.UserSpec;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.Role;

import java.util.List;
import java.util.Locale;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.support.CapabilityParsing;

/**
 * Request body for {@code POST /api/users} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — create/invite a
 * user. Converts to {@link UserSpec} via {@link #toSpec()}.
 *
 * <p>Validation follows this module's "map shapes, let the domain/spec validate" idiom:
 * blank-username / bad-email / blank-password are left to {@link UserSpec} and {@code User}'s own
 * compact constructors (all surfacing as {@code 400} via {@code ApiExceptionHandler}); only the two
 * things the spec cannot express — parsing a membership's {@code groupId} UUID and its {@code role}
 * name — are handled here, each throwing {@link IllegalArgumentException} (also {@code 400}) on a
 * malformed value. {@code role} is matched case-insensitively against {@link Role}, the same idiom
 * as {@code SetLifecycleStateRequest}/{@code CapabilityParsing}.
 *
 * <p><strong>Deferred:</strong> the "a granter may only assign a role/group &le; their own scope"
 * rule (docs/plans/done/U-SCOPE-PLAN.md) is <em>not</em> enforced here or in {@link UserSpec}/{@code
 * UserService} — wave 1 built {@code UserService.create} unscoped and this wave's file scope does
 * not extend into {@code vision-application}. Documented as a follow-up rather than faked at the
 * controller edge.
 *
 * @param username     desired login handle
 * @param displayName  human-readable name
 * @param email        contact address
 * @param password     the plaintext password to hash
 * @param memberships  initial group memberships; {@code null} treated as empty
 * @param enabled      whether the account may authenticate immediately; {@code null} defaults to {@code true}
 */
public record CreateUserRequest(String username, String displayName, String email, String password,
                                List<MembershipRequest> memberships, Boolean enabled) {

    /**
     * One requested membership: which group, which role.
     *
     * @param groupId the group id, as a canonical UUID string
     * @param role    the role name, matched case-insensitively against {@link Role}
     */
    public record MembershipRequest(String groupId, String role) {

        Membership toMembership() {
            if (groupId == null || groupId.isBlank()) {
                throw new IllegalArgumentException("membership groupId must not be blank");
            }
            if (role == null || role.isBlank()) {
                throw new IllegalArgumentException("membership role must not be blank");
            }
            return new Membership(GroupId.of(groupId), parseRole(role));
        }

        private static Role parseRole(String raw) {
            try {
                return Role.valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unrecognized role '" + raw + "'; valid values: " + List.of(Role.values()));
            }
        }
    }

    /**
     * Converts to the application-layer {@link UserSpec}.
     *
     * @return the spec to create
     */
    public UserSpec toSpec() {
        List<Membership> resolved = memberships == null ? List.of()
                : memberships.stream().map(MembershipRequest::toMembership).toList();
        boolean enabledOrDefault = enabled == null || enabled;
        return new UserSpec(username, displayName, email, password, resolved, enabledOrDefault);
    }
}
