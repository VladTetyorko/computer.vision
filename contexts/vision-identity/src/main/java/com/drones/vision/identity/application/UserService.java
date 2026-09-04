package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Membership;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;

import java.util.List;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;

/**
 * Creates and manages {@link User} accounts (docs/plans/done/U-AUTH-PLAN.md, wave 2; management gates added
 * by docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 — deferred slice-2 cleanup; lifecycle + audit added by
 * docs/plans/active/AUTH-ROLES-PLAN.md D13/D14/D15, wave B2; scope-gated methods migrated onto
 * {@link Authority}, wave B6).
 *
 * <p><strong>Management authority is derived from the acting {@link Authority}</strong>, whose wrapped
 * scope's kind maps 1:1 to role: unbounded = ADMIN, groups = MANAGER, else PILOT/empty. Every gated
 * method takes both the acting authority (what may this caller manage) and the acting user's own id
 * (who to attribute the resulting audit entry to — neither an {@link Authority} nor a
 * {@link VisibilityScope} carries identity) and enforces {@link Authority#mayManageOrg()} plus the
 * ≤-own-scope grant rule (see {@link #create(UserSpec, UserId, Authority)}). An
 * {@link Authority#full()} authority (ADMIN / the {@code vision.auth.enabled=false} dev principal)
 * passes every gate — behavior is byte-identical to before these gates existed.
 *
 * <p>{@link #createFirstAdmin(FirstAdminSpec)} is the one exception: it takes neither, since it
 * exists precisely for the moment nobody has authority yet (docs/plans/active/AUTH-ROLES-PLAN.md
 * §3.5's bootstrap latch).
 */
public interface UserService {

    /**
     * Creates a new user, hashing {@link UserSpec#rawPassword()} before storage, subject to the
     * acting user's management authority (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2).
     *
     * <p>Enforcement, in order:
     * <ul>
     *   <li>{@code !acting.mayManageOrg()} → {@link AccessDeniedException} (a PILOT/empty scope may
     *       not create users at all).</li>
     *   <li>A spec with <strong>no memberships</strong> is permitted only for an
     *       {@link VisibilityScope#unbounded() unbounded} (ADMIN) scope — a manager must place a new
     *       user in a group they manage, which prevents a manager minting unscoped users that would
     *       fall outside anyone's subtree.</li>
     *   <li>For each membership: its group must satisfy {@link VisibilityScope#includesGroup} (else
     *       {@link AccessDeniedException} "cannot grant membership in a group outside your scope"),
     *       and its {@link com.drones.vision.identity.domain.model.Role} must not exceed the acting
     *       scope's grant ceiling by ordinal (computed by {@code DefaultUserService#maxGrantableRole},
     *       private — granting roles is user administration, not visibility, so it does not live on
     *       {@link VisibilityScope} itself) (else {@link AccessDeniedException} "cannot grant a role
     *       above your own").</li>
     * </ul>
     *
     * <p>The created user's {@link User#mustChangePassword()} is always {@code true}
     * (docs/plans/active/AUTH-ROLES-PLAN.md D13, wave B2): this path is always an admin/manager
     * choosing a temporary password on someone else's behalf, never the account holder's own choice.
     * Records an {@link com.drones.vision.platform.AuditAction#CREATED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#USER}).
     *
     * @param spec   the new user's shape
     * @param actor  the acting user's own id, for audit attribution
     * @param acting the acting user's authority
     * @return the created, persisted user
     * @throws AccessDeniedException if {@code acting} may not create this user (403)
     * @throws IllegalStateException if {@link UserSpec#username()} is already taken (409)
     */
    User create(UserSpec spec, UserId actor, Authority acting);

    /**
     * Lists the users visible to the acting scope (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2):
     * {@link VisibilityScope#unbounded() unbounded} → every user; a {@link VisibilityScope.Kind#GROUPS
     * groups} scope → only users with at least one membership in a group the scope
     * {@link VisibilityScope#includesGroup includes}; any other scope → an empty list.
     *
     * @param acting the acting user's visibility scope
     * @return the visible users
     */
    List<User> list(VisibilityScope acting);

    /**
     * Enables or disables a user's ability to authenticate, subject to management authority. Idempotent.
     *
     * <p>{@code !acting.mayManageOrg()} → {@link AccessDeniedException}. If {@code acting} is not
     * unbounded, the target must have at least one membership whose group the scope
     * {@link VisibilityScope#includesGroup includes} — a manager may only enable/disable users
     * within their own subtree. An unbounded scope is unrestricted.
     *
     * <p>Records an {@link com.drones.vision.platform.AuditAction#ACTIVATED}/{@link
     * com.drones.vision.platform.AuditAction#DEACTIVATED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#USER}) only when the enabled state actually
     * changes — a no-op call (already in the requested state) writes nothing, the same "only the
     * facts that changed" idiom {@code DefaultCameraPoseService#delete} uses (vision-map).
     *
     * @param id      the user to update
     * @param enabled the new enabled state
     * @param actor   the acting user's own id, for audit attribution
     * @param acting  the acting user's authority
     * @return the updated user
     * @throws AccessDeniedException            if {@code acting} may not manage this user (403)
     * @throws java.util.NoSuchElementException if {@code id} is unknown (404)
     */
    User setEnabled(UserId id, boolean enabled, UserId actor, Authority acting);

    /**
     * Wholesale-replaces a user's group memberships, subject to the same management authority and
     * ≤-own-scope grant ceiling as {@link #create(UserSpec, UserId, Authority)}
     * (docs/plans/active/AUTH-ROLES-PLAN.md D14, wave B2 — memberships were write-once before this).
     *
     * <p>Enforcement, in order: {@code !acting.mayManageOrg()} → {@link AccessDeniedException};
     * the target must already have at least one membership {@code acting} {@link
     * VisibilityScope#includesGroup includes} (an unbounded scope is exempt) — a manager may not
     * reach into a user entirely outside their subtree just to add themselves a foothold; an empty
     * {@code memberships} is rejected the same way {@link #create} rejects it for a non-unbounded
     * scope; then every membership in the new set is checked against {@link
     * VisibilityScope#includesGroup} and the grant ceiling, exactly as {@link #create} checks them.
     *
     * <p>Diffs the old and new membership sets and records one {@link
     * com.drones.vision.platform.AuditAction#GRANTED} entry per membership added and one {@link
     * com.drones.vision.platform.AuditAction#REVOKED} entry per membership removed (each targeting
     * {@link com.drones.vision.platform.AuditTargetType#GROUP}, keyed by that membership's group id)
     * — a membership whose role changed produces one of each, which is the correct reading: the old
     * role in that group was revoked and the new one granted.
     *
     * @param id           the user whose memberships to replace
     * @param memberships  the complete new membership set (not a delta)
     * @param actor        the acting user's own id, for audit attribution
     * @param acting       the acting user's authority
     * @return the updated user
     * @throws AccessDeniedException            if {@code acting} may not edit this user, may not
     *                                           reach one of the requested groups, or would grant a
     *                                           role above its own ceiling (403)
     * @throws java.util.NoSuchElementException if {@code id} is unknown (404)
     */
    User setMemberships(UserId id, List<Membership> memberships, UserId actor, Authority acting);

    /**
     * Admin-resets a user's password, subject to the same management authority as {@link
     * #setEnabled} (docs/plans/active/AUTH-ROLES-PLAN.md D13, wave B2).
     *
     * <p>Always sets {@link User#mustChangePassword()} to {@code true} — the admin is choosing this
     * password on the account holder's behalf, so it is a temporary credential by definition, same
     * reasoning as {@link #create}. Records an {@link com.drones.vision.platform.AuditAction#UPDATED}
     * entry ({@link com.drones.vision.platform.AuditTargetType#USER}) unconditionally: unlike
     * {@link #setEnabled}, there is no meaningful "no-op" password reset to skip auditing —
     * {@link com.drones.vision.identity.domain.port.PasswordHasherPort#hash(String)}'s contract
     * forbids comparing hashes for equality, so every call is a genuine change.
     *
     * @param rawPassword the new plaintext password to hash and store; must not be blank
     * @param id          the user whose password to reset
     * @param actor       the acting user's own id, for audit attribution
     * @param acting      the acting user's authority
     * @return the updated user
     * @throws AccessDeniedException            if {@code acting} may not manage this user (403)
     * @throws java.util.NoSuchElementException if {@code id} is unknown (404)
     * @throws IllegalArgumentException         if {@code rawPassword} is blank
     */
    User setPassword(UserId id, String rawPassword, UserId actor, Authority acting);

    /**
     * Bootstraps the very first {@link User} on a fresh station — always granted exactly one
     * {@link com.drones.vision.identity.domain.model.Role#ADMIN} membership, always enabled, and
     * never requiring a forced password change (docs/plans/active/AUTH-ROLES-PLAN.md §3.5, wave B2;
     * see {@link FirstAdminSpec}'s own javadoc for why it is not {@link UserSpec}).
     *
     * <p>Takes no acting scope: this method exists for the moment nobody has authority yet. Refuses
     * once any enabled user already holds an {@code ADMIN} membership — the one-way latch behind
     * {@code POST /api/auth/bootstrap}'s {@code 409 ALREADY_INITIALIZED} — independently of {@link
     * com.drones.vision.identity.application.AuthService#adminExists()}, which the API edge is
     * expected to also check before ever calling this, but a bootstrap endpoint racing itself must
     * not be the only thing standing between a station and a second, unintended founder-admin.
     *
     * <p>Records an {@link com.drones.vision.platform.AuditAction#CREATED} entry
     * ({@link com.drones.vision.platform.AuditTargetType#USER}), attributed to the newly created
     * admin themselves — there is no other actor to attribute a bootstrap to.
     *
     * @param spec the new administrator's shape, including the group their {@code ADMIN} membership
     *             is granted in
     * @return the created, persisted first administrator
     * @throws IllegalStateException if an enabled administrator already exists, or if
     *                                {@link FirstAdminSpec#username()} is already taken (both 409)
     */
    User createFirstAdmin(FirstAdminSpec spec);
}
