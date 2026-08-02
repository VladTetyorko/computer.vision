package com.drones.vision.application.identity;

import com.drones.vision.domain.model.User;
import com.drones.vision.domain.model.UserId;

import java.util.List;
import com.drones.vision.application.scope.AccessDeniedException;
import com.drones.vision.application.scope.VisibilityScope;

/**
 * Creates and manages {@link User} accounts (docs/U-AUTH-PLAN.md, wave 2; management gates added
 * by docs/U-SCOPE-PLAN.md, U-e slice 2 — deferred slice-2 cleanup).
 *
 * <p><strong>Management authority is derived from the acting {@link VisibilityScope}</strong>, whose
 * kind maps 1:1 to role: unbounded = ADMIN, groups = MANAGER, else PILOT/empty. Every method takes
 * the acting scope and enforces {@link VisibilityScope#canManageOrg()} plus the ≤-own-scope grant
 * rule (see {@link #create(UserSpec, VisibilityScope)}). An {@link VisibilityScope#unbounded()}
 * scope (ADMIN / the {@code vision.auth.enabled=false} dev principal) passes every gate — behavior
 * is byte-identical to before these gates existed.
 */
public interface UserService {

    /**
     * Creates a new user, hashing {@link UserSpec#rawPassword()} before storage, subject to the
     * acting user's management authority (docs/U-SCOPE-PLAN.md, U-e slice 2).
     *
     * <p>Enforcement, in order:
     * <ul>
     *   <li>{@code !acting.canManageOrg()} → {@link AccessDeniedException} (a PILOT/empty scope may
     *       not create users at all).</li>
     *   <li>A spec with <strong>no memberships</strong> is permitted only for an
     *       {@link VisibilityScope#unbounded() unbounded} (ADMIN) scope — a manager must place a new
     *       user in a group they manage, which prevents a manager minting unscoped users that would
     *       fall outside anyone's subtree.</li>
     *   <li>For each membership: its group must satisfy {@link VisibilityScope#includesGroup} (else
     *       {@link AccessDeniedException} "cannot grant membership in a group outside your scope"),
     *       and its {@link com.drones.vision.domain.model.Role} must not exceed
     *       {@link VisibilityScope#maxGrantableRole()} by ordinal (else {@link AccessDeniedException}
     *       "cannot grant a role above your own").</li>
     * </ul>
     *
     * @param spec   the new user's shape
     * @param acting the acting user's visibility scope
     * @return the created, persisted user
     * @throws AccessDeniedException if {@code acting} may not create this user (403)
     * @throws IllegalStateException if {@link UserSpec#username()} is already taken (409)
     */
    User create(UserSpec spec, VisibilityScope acting);

    /**
     * Lists the users visible to the acting scope (docs/U-SCOPE-PLAN.md, U-e slice 2):
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
     * <p>{@code !acting.canManageOrg()} → {@link AccessDeniedException}. If {@code acting} is not
     * unbounded, the target must have at least one membership whose group the scope
     * {@link VisibilityScope#includesGroup includes} — a manager may only enable/disable users
     * within their own subtree. An unbounded scope is unrestricted.
     *
     * @param id      the user to update
     * @param enabled the new enabled state
     * @param acting  the acting user's visibility scope
     * @return the updated user
     * @throws AccessDeniedException            if {@code acting} may not manage this user (403)
     * @throws java.util.NoSuchElementException if {@code id} is unknown (404)
     */
    User setEnabled(UserId id, boolean enabled, VisibilityScope acting);
}
