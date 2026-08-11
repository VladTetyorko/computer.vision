package com.drones.vision.identity.application;

import com.drones.vision.identity.domain.model.Group;

import java.util.List;
import com.drones.vision.platform.AccessDeniedException;
import com.drones.vision.platform.VisibilityScope;

/**
 * Creates and lists {@link Group}s (docs/plans/done/U-AUTH-PLAN.md, wave 2; management gates added by
 * docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 — deferred slice-2 cleanup).
 *
 * <p><strong>Management authority is derived from the acting {@link VisibilityScope}</strong> (kind
 * maps 1:1 to role: unbounded = ADMIN, groups = MANAGER, else PILOT/empty). Both methods take the
 * acting scope; an {@link VisibilityScope#unbounded()} scope passes every gate — behavior is
 * byte-identical to before these gates existed.
 */
public interface GroupService {

    /**
     * Creates a new group, subject to the acting user's management authority
     * (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2).
     *
     * <p>{@code !acting.canManageOrg()} → {@link AccessDeniedException}. A <strong>root</strong>
     * group (null {@link GroupSpec#parentGroupId()}) may be created only by an
     * {@link VisibilityScope#unbounded() unbounded} (ADMIN) scope — a manager creating a root group
     * is refused. When a parent is given, {@link VisibilityScope#includesGroup} must hold for it,
     * else {@link AccessDeniedException}.
     *
     * @param spec   the new group's shape
     * @param acting the acting user's visibility scope
     * @return the created, persisted group
     * @throws AccessDeniedException            if {@code acting} may not create this group (403)
     * @throws java.util.NoSuchElementException if {@link GroupSpec#parentGroupId()} is non-null
     *                                           and unknown (404)
     */
    Group create(GroupSpec spec, VisibilityScope acting);

    /**
     * Lists the groups visible to the acting scope, sorted by name (case-insensitive) for a stable
     * UI order: {@link VisibilityScope#unbounded() unbounded} → every group; a
     * {@link VisibilityScope.Kind#GROUPS groups} scope → only groups the scope
     * {@link VisibilityScope#includesGroup includes}; any other scope → an empty list.
     *
     * @param acting the acting user's visibility scope
     * @return the visible groups
     */
    List<Group> list(VisibilityScope acting);
}
