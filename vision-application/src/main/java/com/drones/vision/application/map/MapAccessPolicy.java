package com.drones.vision.application.map;

import com.drones.vision.domain.model.AccessLevel;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerGrant;
import com.drones.vision.domain.model.LayerKind;
import com.drones.vision.domain.model.MapLayer;
import com.drones.vision.domain.model.Role;
import com.drones.vision.domain.model.UserId;

import java.util.Objects;
import java.util.Set;

/**
 * Resolves effective access to a {@link MapLayer} for one viewer (docs/MAP-REWORK-PLAN.md §3) —
 * the map's authorization model. Pure: no ports, no mutable state, safe to share as a singleton.
 *
 * <h2>Why identity, not {@code VisibilityScope}</h2>
 * This policy resolves from the viewer's own identity and group memberships ({@link Viewer}), never
 * from {@code com.drones.vision.application.scope.VisibilityScope}. That is a deliberate departure
 * from how every other scoped read/command in this module works, made to avoid a trap this module's
 * own history already hit once: {@code VisibilityScope#includesGroup} is hard-{@code false} for a
 * {@code ASSIGNED_ASSETS} (PILOT) scope, because that scope carries no group information at all —
 * only a set of individually assigned asset ids. Gating map visibility on {@code includesGroup} the
 * way, say, {@code DefaultUserService}/{@code DefaultGroupService} gate org management would make
 * every {@link LayerKind#TEAM} layer (and therefore every mark/drawing on one) structurally
 * unreachable for a PILOT — including their own team's layer — exactly the bug the original
 * {@code DefaultMarkService#list()} shipped with and was later revised away from (see this module's
 * MODULE.md, "Design history worth keeping", and docs/MAP-REWORK-PLAN.md §1's own "Known trap"
 * note). A map viewer's visibility is a property of which groups they actually belong to, not of
 * which asset-management tier their role falls into, so this policy takes plain group membership
 * (plus role, for the COP/organization-wide rules) as its input instead.
 *
 * <h2>Rule table (docs/MAP-REWORK-PLAN.md §3, frozen)</h2>
 * Effective access to a layer is the <b>max</b> (by {@link AccessLevel}'s own least&rarr;most
 * permissive ordinal ordering) across every rule that applies:
 * <ol>
 *   <li>{@link Role#ADMIN} &rarr; {@link AccessLevel#MANAGE} on every layer.</li>
 *   <li>{@link LayerKind#COP} &rarr; {@link AccessLevel#VIEW} to everyone; {@link
 *       AccessLevel#MANAGE} to a {@link Role#MANAGER} (any group) or {@link Role#ADMIN}.</li>
 *   <li>{@code layer.ownership().ownerId().equals(viewer.userId())} &rarr; {@link
 *       AccessLevel#MANAGE} (the layer's own creator always manages it).</li>
 *   <li>{@link LayerKind#TEAM} and {@code layer.ownership().groupId()} in {@link Viewer#groups()}
 *       &rarr; {@link AccessLevel#CONTRIBUTE} (a team member contributes to their own team layer).</li>
 *   <li>{@link Role#MANAGER} whose {@link Viewer#groups()} include {@code
 *       layer.ownership().groupId()} &rarr; {@link AccessLevel#MANAGE}.</li>
 *   <li>An explicit {@code LayerGrant(USER, viewer.userId(), L)} &rarr; {@code L}.</li>
 *   <li>An explicit {@code LayerGrant(GROUP, g, L)} where {@code g} is in {@link Viewer#groups()}
 *       &rarr; {@code L}.</li>
 * </ol>
 * No applicable rule &rarr; no access ({@link #accessTo} returns {@code null}).
 *
 * <h2>Threading</h2>
 * Stateless; every method is safe for concurrent use.
 */
public final class MapAccessPolicy {

    /**
     * The identity a map access decision is made for: who they are, which groups they belong to
     * (their own direct memberships — <b>and</b>, for a manager, the subtree they manage, expanded
     * the same way {@code ScopeResolver} expands a manager's group tree; that expansion is the
     * caller's responsibility, typically {@code CurrentUser#viewer()} in {@code vision-api}, Wave C),
     * and their highest {@link Role}. A user with no memberships at all still needs some {@link
     * Role} value here since this component is non-nullable — Wave C's {@code CurrentUser#viewer()}
     * is responsible for picking one (the least-privileged {@link Role#PILOT} is the natural choice)
     * for that edge case; this class makes no assumption about how {@code topRole} was derived.
     *
     * @param userId   the viewer's identity
     * @param groups   every group whose membership should count toward this viewer's access
     *                 (defensively copied; {@code null} normalizes to empty)
     * @param topRole  the viewer's highest role
     */
    public record Viewer(UserId userId, Set<GroupId> groups, Role topRole) {

        public Viewer {
            Objects.requireNonNull(userId, "userId must not be null");
            groups = groups == null ? Set.of() : Set.copyOf(groups);
            Objects.requireNonNull(topRole, "topRole must not be null");
        }
    }

    /**
     * Computes the highest {@link AccessLevel} {@code viewer} has on {@code layer} — the max across
     * every applicable rule in the class javadoc's table.
     *
     * @param viewer who is asking
     * @param layer  the layer to check
     * @return the highest applicable access level, or {@code null} if no rule grants any access at all
     */
    public AccessLevel accessTo(Viewer viewer, MapLayer layer) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(layer, "layer must not be null");

        AccessLevel best = null;

        // Rule 1: ADMIN manages everything.
        if (viewer.topRole() == Role.ADMIN) {
            best = max(best, AccessLevel.MANAGE);
        }

        // Rule 2: COP is viewable by everyone; MANAGER (any group) or ADMIN manages it.
        if (layer.kind() == LayerKind.COP) {
            best = max(best, viewer.topRole() == Role.MANAGER || viewer.topRole() == Role.ADMIN
                    ? AccessLevel.MANAGE
                    : AccessLevel.VIEW);
        }

        // Rule 3: the layer's own creator always manages it.
        if (layer.ownership().ownerId().equals(viewer.userId())) {
            best = max(best, AccessLevel.MANAGE);
        }

        // Rule 4: a TEAM member contributes to their own team's layer.
        if (layer.kind() == LayerKind.TEAM && viewer.groups().contains(layer.ownership().groupId())) {
            best = max(best, AccessLevel.CONTRIBUTE);
        }

        // Rule 5: a MANAGER manages any layer owned by a group in their scope.
        if (viewer.topRole() == Role.MANAGER && viewer.groups().contains(layer.ownership().groupId())) {
            best = max(best, AccessLevel.MANAGE);
        }

        // Rules 6/7: explicit grants, per-user or per-group.
        for (LayerGrant grant : layer.grants()) {
            boolean applies = switch (grant.subjectType()) {
                case USER -> grant.subjectId().equals(viewer.userId().value());
                case GROUP -> viewer.groups().contains(new GroupId(grant.subjectId()));
            };
            if (applies) {
                best = max(best, grant.level());
            }
        }

        return best;
    }

    /**
     * @param viewer who is asking
     * @param layer  the layer to check
     * @return {@code true} iff {@link #accessTo} is at least {@link AccessLevel#VIEW} (i.e.
     *         non-{@code null})
     */
    public boolean canView(Viewer viewer, MapLayer layer) {
        return accessTo(viewer, layer) != null;
    }

    /**
     * @param viewer who is asking
     * @param layer  the layer to check
     * @return {@code true} iff {@link #accessTo} is at least {@link AccessLevel#CONTRIBUTE}
     */
    public boolean canContribute(Viewer viewer, MapLayer layer) {
        return atLeast(accessTo(viewer, layer), AccessLevel.CONTRIBUTE);
    }

    /**
     * @param viewer who is asking
     * @param layer  the layer to check
     * @return {@code true} iff {@link #accessTo} is at least {@link AccessLevel#MANAGE}
     */
    public boolean canManage(Viewer viewer, MapLayer layer) {
        return atLeast(accessTo(viewer, layer), AccessLevel.MANAGE);
    }

    private static boolean atLeast(AccessLevel actual, AccessLevel required) {
        return actual != null && actual.ordinal() >= required.ordinal();
    }

    private static AccessLevel max(AccessLevel a, AccessLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
