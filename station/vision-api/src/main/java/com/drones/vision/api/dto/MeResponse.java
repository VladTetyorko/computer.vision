package com.drones.vision.api.dto;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.platform.Authority;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import com.drones.vision.api.security.CurrentUser;

/**
 * Response body for {@code GET /api/auth/me}, {@code POST /api/auth/login} (docs/plans/done/U-AUTH-PLAN.md,
 * wave 3's frozen wire contract; widened by docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B3) — the
 * current identity as the SPA needs it.
 *
 * <p>{@code authEnabled} tells the SPA whether login is real at all: {@code false} means the
 * backend is running with {@code vision.auth.enabled=false} and this response is the fixed dev
 * admin (no login screen should show); {@code true} means this reflects a real authenticated
 * session. {@code email}/{@code topRole} are omitted when absent (a user with no memberships has no
 * {@code topRole}).
 *
 * <p>{@code capabilities} is {@link Authority#capabilities()} by name — what this session may
 * <em>do</em>, deployment/org-wide, independent of what it may see. {@code scopeKind} is {@link
 * com.drones.vision.platform.VisibilityScope.Kind}'s name — {@code UNBOUNDED}/{@code GROUPS}/{@code
 * ASSIGNED_ASSETS} — so the SPA can render the right "why don't I see X" affordance without
 * re-deriving it from {@code topRole}. {@code mustChangePassword} mirrors {@link
 * User#mustChangePassword()} — {@code true} forces the SPA to a change-password gate before
 * anything else.
 *
 * @param userId              the acting user's id, as a canonical UUID string
 * @param username            login handle
 * @param displayName         human-readable name
 * @param email               contact address, or absent if unknown
 * @param memberships         the user's group memberships (group id + resolved name + role); may be empty
 * @param topRole             the highest role held across memberships, or absent when the user has none
 * @param authEnabled         whether {@code vision.auth.enabled} is on (real session) or off (dev admin)
 * @param capabilities        the verbs this session holds, deployment/org-wide (enum names)
 * @param scopeKind           which {@link com.drones.vision.platform.VisibilityScope.Kind} this session's
 *                            visibility resolves to
 * @param mustChangePassword  whether the next action must be a forced password change
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeResponse(String userId, String username, String displayName, String email,
                         List<MembershipResponse> memberships, String topRole, boolean authEnabled,
                         List<String> capabilities, String scopeKind, boolean mustChangePassword) {

    /**
     * One group membership on the wire.
     *
     * @param groupId   the group id, as a canonical UUID string
     * @param groupName the resolved group name, or a fallback marker if the group is unknown
     * @param role      the role held in that group (the enum name)
     */
    public record MembershipResponse(String groupId, String groupName, String role) {
    }

    /**
     * Maps an authenticated domain {@link User} to its wire representation, resolving each
     * membership's group name via {@code groupNames} (an unknown group falls back to its id string
     * so the response is still well-formed).
     *
     * @param user        the authenticated user
     * @param groupNames  group id string → group name; may lack an entry for a stale membership
     * @param authEnabled whether auth is enabled (always {@code true} for a real user)
     * @param authority   the user's resolved authority (scope + capabilities), for {@code
     *                    capabilities}/{@code scopeKind}
     * @return the response body for {@code user}
     */
    public static MeResponse from(User user, Function<String, String> groupNames, boolean authEnabled,
                                  Authority authority) {
        List<MembershipResponse> memberships = user.memberships().stream()
                .map(m -> toMembership(m, groupNames))
                .toList();
        String topRole = user.topRole().map(Enum::name).orElse(null);
        List<String> capabilities = authority.capabilities().stream().map(Enum::name).sorted().toList();
        return new MeResponse(user.id().value().toString(), user.username(), user.displayName(),
                user.email(), memberships, topRole, authEnabled, capabilities,
                authority.scope().kind().name(), user.mustChangePassword());
    }

    private static MembershipResponse toMembership(Membership membership, Function<String, String> groupNames) {
        String groupId = membership.groupId().value().toString();
        String name = groupNames.apply(groupId);
        return new MembershipResponse(groupId, name != null ? name : groupId, membership.role().name());
    }

    /**
     * The fixed dev-admin identity returned by {@code /api/auth/*} when {@code
     * vision.auth.enabled=false} — a full-privilege {@code ADMIN} answer so the SPA renders the app
     * exactly as it did before auth existed, with {@code authEnabled=false} suppressing any login
     * screen. Every {@link Authority} capability is held, {@code scopeKind} is {@code UNBOUNDED}, and
     * {@code mustChangePassword} is {@code false} — the dev principal never needs to change anything.
     *
     * @param userId    the dev principal's user id (from {@code CurrentUser})
     * @param groupId   the dev principal's group id (from {@code CurrentUser}'s ownership)
     * @return the dev-admin response body
     */
    public static MeResponse devAdmin(String userId, String groupId) {
        List<String> capabilities = Authority.full().capabilities().stream().map(Enum::name).sorted().toList();
        return new MeResponse(userId, "admin", "Administrator", "admin@vision.local",
                List.of(new MembershipResponse(groupId, "Root", "ADMIN")), "ADMIN", false,
                capabilities, "UNBOUNDED", false);
    }

    /**
     * Convenience over {@link #from(User, Function, boolean, Authority)} taking a plain map of group
     * names.
     *
     * @param user        the authenticated user
     * @param groupNames  group id string → group name
     * @param authEnabled whether auth is enabled
     * @param authority   the user's resolved authority
     * @return the response body for {@code user}
     */
    public static MeResponse from(User user, Map<String, String> groupNames, boolean authEnabled,
                                  Authority authority) {
        return from(user, groupNames::get, authEnabled, authority);
    }
}
