package com.drones.vision.identity.application.scope;

import com.drones.vision.identity.domain.model.Role;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;

/**
 * Resolves a {@link User}'s {@link VisibilityScope} (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1).
 * One interface, one implementation ({@link DefaultScopeResolver}), mirroring every other service
 * in this package.
 *
 * <p>This is the single place role + group tree + pilot assignments are turned into "what may this
 * user see." A driving adapter (vision-api's {@code CurrentUser}) calls it once per request and
 * threads the result into the scoped read/command methods; when auth is disabled the dev principal
 * resolves to {@link VisibilityScope#unbounded()}, so nothing downstream changes.
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use; all shared state lives behind driven ports.
 */
public interface ScopeResolver {

    /**
     * Computes the visibility scope for a user.
     *
     * <p>Precedence, highest privilege first:
     * <ol>
     *   <li>an ADMIN membership anywhere &rarr; {@link VisibilityScope#unbounded()};</li>
     *   <li>otherwise any MANAGER membership &rarr; {@link VisibilityScope#groups(java.util.Set)} over
     *       the union of each MANAGER group's subtree (self + descendants);</li>
     *   <li>otherwise (PILOT-only, or no membership at all) &rarr;
     *       {@link VisibilityScope#assignedAssets(java.util.Set)} over the pilot's assignments — which
     *       is the empty set, and so includes nothing, for a user with no assignments.</li>
     * </ol>
     *
     * @param user the acting user
     * @return the user's visibility scope
     */
    VisibilityScope scopeFor(User user);

    /**
     * Computes the {@link Authority} — both axes at once — for a user (docs/plans/active/AUTH-ROLES-PLAN.md
     * §3.3, wave B1): {@link #scopeFor(User)} paired with {@link RoleAuthority#capabilitiesOf(
     * com.drones.vision.identity.domain.model.Role)} for the user's {@link User#topRole()}.
     *
     * <p>A user with no memberships (empty {@link User#topRole()}) holds no capabilities — the same
     * safe default {@link Role#VIEWER} gets, and for the same reason: nothing to grant authority
     * from. <strong>Precedence between {@link #scopeFor(User)} and this method is unchanged from
     * before this method existed</strong> — see docs/plans/active/AUTH-ROLES-PLAN.md §3.6's staging
     * rule: a {@link Role#VIEWER}-only user resolves through {@link #scopeFor(User)}'s existing
     * "PILOT-only, or no membership at all" branch (assigned-assets, ordinarily empty for a viewer),
     * not a widened group scope — that widening is wave B6's, gated on every {@code
     * canManageOrg()}/{@code canManage()}/{@code canAdminister()} call site having first migrated
     * onto {@link Authority}, so a {@code VIEWER} handed a wide scope today could not accidentally
     * pass one of those deprecated predicates directly.
     *
     * @param user the acting user
     * @return the user's authority — scope plus capabilities
     */
    Authority authorityFor(User user);
}
