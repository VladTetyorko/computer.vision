package com.drones.vision.platform;

import com.drones.vision.kernel.Ownership;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a caller may <em>do</em> at the deployment/org level — the authority axis
 * (docs/plans/active/AUTH-ROLES-PLAN.md §3.1/§3.3), sibling to {@link VisibilityScope}'s "what may
 * this caller see."
 *
 * <p>Wraps a {@link VisibilityScope} rather than copying or replacing it — {@link #scope()} is the
 * same value every scoped read already threads through, unchanged. A capability alone is never the
 * whole answer: {@link #mayManageOrg()}/{@link #mayAdminister()}/{@link #mayManageFleet(Ownership)}
 * additionally require the wrapped scope's own {@link VisibilityScope#kind()} to reach that
 * resource — {@link VisibilityScope}'s three visibility-only predicates that used to answer this
 * (docs/plans/active/AUTH-ROLES-PLAN.md wave B6) were deleted once every caller moved onto this type,
 * so the same kind-based logic now lives here instead. A capability says "this role is the kind of
 * role that may do this at all," the scope says "and this specific request's boundary reaches this
 * specific resource." Both must hold.
 *
 * <p>Deliberately carries <strong>no per-asset command verb</strong> — "may this caller fly this one
 * aircraft" is {@code AssetAuthority}'s question (docs/plans/active/AUTH-ROLES-PLAN.md §3.9,
 * {@code vision-api}, a request-scoped bean outside this module's reach), not this type's. This type
 * only ever answers deployment/org-wide questions, so there is exactly one place — the request-scoped
 * bean — to look for the per-aircraft answer.
 *
 * <p><strong>{@link #full()} is the identity element.</strong> It wraps {@link
 * VisibilityScope#unbounded()} and holds every {@link Capability}, so every existing test double,
 * every {@code PrincipalResolver.fixed(...)}, and the dev principal all get it and every gate that
 * moves onto {@code Authority} answers exactly as it did under a bare {@link VisibilityScope}
 * before this type existed — the same "unbounded is the guardrail" trick
 * docs/plans/done/U-SCOPE-PLAN.md used for {@link VisibilityScope} itself. There is no new feature
 * flag: {@code vision.auth.enabled} is still the only switch.
 *
 * @param scope        the read axis — what this caller may see; never {@code null}
 * @param capabilities the verbs this caller holds, deployment/org-wide; defensively copied, may be
 *                     empty (a {@link com.drones.vision.identity.domain.model.Role#VIEWER VIEWER}
 *                     account holds none)
 */
public record Authority(VisibilityScope scope, Set<Capability> capabilities) {

    public Authority {
        Objects.requireNonNull(scope, "scope must not be null");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
    }

    /**
     * Whether this caller may manage the organization — create/enable users, create groups, and see
     * the management lists at all.
     *
     * @return {@code true} iff {@link Capability#MANAGE_ORG} is held <strong>and</strong> {@link
     *         #scope()}'s {@link VisibilityScope#kind()} is {@link VisibilityScope.Kind#UNBOUNDED} or
     *         {@link VisibilityScope.Kind#GROUPS}
     */
    public boolean mayManageOrg() {
        VisibilityScope.Kind kind = scope.kind();
        return capabilities.contains(Capability.MANAGE_ORG)
                && (kind == VisibilityScope.Kind.UNBOUNDED || kind == VisibilityScope.Kind.GROUPS);
    }

    /**
     * Whether this caller may administer (rename, deactivate, delete, reassign the devices of) the
     * asset owned by {@code ownership} — fleet management within scope.
     *
     * <p>{@code false} for {@link VisibilityScope.Kind#ASSIGNED_ASSETS} regardless of capabilities or
     * ownership: a PILOT's scope grants no more than seeing (and flying) the aircraft assigned to
     * them, never management of it — the same distinction {@link VisibilityScope}'s deleted {@code
     * canManage(Ownership)} predicate used to draw.
     *
     * @param ownership the asset's ownership
     * @return {@code true} iff {@link Capability#MANAGE_FLEET} is held <strong>and</strong> {@link
     *         #scope()} reaches {@code ownership}'s group — always for {@link
     *         VisibilityScope.Kind#UNBOUNDED}, only when the group is in {@link VisibilityScope#groups()}
     *         for {@link VisibilityScope.Kind#GROUPS}, never for {@link
     *         VisibilityScope.Kind#ASSIGNED_ASSETS}
     */
    public boolean mayManageFleet(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        if (!capabilities.contains(Capability.MANAGE_FLEET)) {
            return false;
        }
        return switch (scope.kind()) {
            case UNBOUNDED -> true;
            case GROUPS -> scope.groups().contains(ownership.groupId());
            case ASSIGNED_ASSETS -> false;
        };
    }

    /**
     * Whether this caller may take a deployment-global action with no group boundary — promote the
     * live CV model, start a training job, or anything else with a blast radius wider than one
     * group's fleet.
     *
     * @return {@code true} iff {@link Capability#MANAGE_ORG} is held <strong>and</strong> {@link
     *         #scope()} is {@link VisibilityScope#isUnbounded()}
     */
    public boolean mayAdminister() {
        return capabilities.contains(Capability.MANAGE_ORG) && scope.isUnbounded();
    }

    /**
     * The unbounded, every-capability authority — ADMIN, the dev principal (auth disabled), and
     * every fixed test double. The identity element this type's whole backward-compatibility
     * guarantee rests on: every gate that switches from a bare {@link VisibilityScope} onto {@code
     * Authority} answers identically for a caller holding this value.
     *
     * @return unbounded scope, every capability
     */
    public static Authority full() {
        return new Authority(VisibilityScope.unbounded(), EnumSet.allOf(Capability.class));
    }
}
