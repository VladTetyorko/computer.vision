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
 * whole answer: {@link #mayManageOrg()} and {@link #mayAdminister()} additionally require the wrapped
 * scope's own {@link VisibilityScope#canManageOrg()}/{@link VisibilityScope#canAdminister()}, and
 * {@link #mayManageFleet(Ownership)} additionally requires {@link VisibilityScope#canManage(Ownership)}
 * — a capability says "this role is the kind of role that may do this at all," the scope says
 * "and this specific request's boundary reaches this specific resource." Both must hold.
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
     *         VisibilityScope#canManageOrg()} holds on {@link #scope()}
     */
    public boolean mayManageOrg() {
        return capabilities.contains(Capability.MANAGE_ORG) && scope.canManageOrg();
    }

    /**
     * Whether this caller may administer (rename, deactivate, delete, reassign the devices of) the
     * asset owned by {@code ownership} — fleet management within scope.
     *
     * @param ownership the asset's ownership
     * @return {@code true} iff {@link Capability#MANAGE_FLEET} is held <strong>and</strong> {@link
     *         VisibilityScope#canManage(Ownership)} holds on {@link #scope()} for {@code ownership}
     */
    public boolean mayManageFleet(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        return capabilities.contains(Capability.MANAGE_FLEET) && scope.canManage(ownership);
    }

    /**
     * Whether this caller may take a deployment-global action with no group boundary — promote the
     * live CV model, start a training job, or anything else {@link VisibilityScope#canAdminister()}
     * itself gates.
     *
     * @return {@code true} iff {@link Capability#MANAGE_ORG} is held <strong>and</strong> {@link
     *         VisibilityScope#canAdminister()} holds on {@link #scope()}
     */
    public boolean mayAdminister() {
        return capabilities.contains(Capability.MANAGE_ORG) && scope.canAdminister();
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
