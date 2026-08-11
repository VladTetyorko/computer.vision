package com.drones.vision.application.scope;

import com.drones.vision.identity.domain.model.User;

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
}
