package com.drones.vision.platform;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;

import java.util.Objects;
import java.util.Set;

/**
 * What a request may see: the resolved boundary of one user's visibility (docs/plans/done/U-SCOPE-PLAN.md,
 * U-e slice 2, feature 1). Computed once per request from the acting user by {@code ScopeResolver}
 * (vision-application) and threaded into the user-facing read paths, which filter by
 * {@link #includes(AssetId, Ownership)}.
 *
 * <p>Three cases, one per privilege model:
 * <ul>
 *   <li>{@link #unbounded()} — ADMIN, and the dev principal when {@code vision.auth.enabled=false}.
 *       {@link #includes(AssetId, Ownership)} is always {@code true}, so the default-off build behaves
 *       exactly as it does today (this is the slice's guardrail — a scoped read given an unbounded
 *       scope returns precisely the unscoped result).</li>
 *   <li>{@link #groups(Set)} — MANAGER. {@link #includes(AssetId, Ownership)} is {@code true} iff the
 *       given {@link Ownership#groupId()} is in the scope's group set (the manager's group subtree,
 *       which {@code ScopeResolver} expands from the group tree).</li>
 *   <li>{@link #assignedAssets(Set)} — PILOT. {@link #includes(AssetId, Ownership)} is {@code true}
 *       iff the given asset id is in the scope's assigned-asset set.</li>
 * </ul>
 *
 * <p>An empty {@code groups} or {@code assignedAssets} scope includes nothing — a user with no
 * memberships and no assignments sees nothing until assigned (documented on {@code ScopeResolver}).
 * Both collections are defensively copied to immutable sets in the compact constructor.
 *
 * <p><strong>Visibility is not authority.</strong> {@link #includes(AssetId, Ownership)}/{@link
 * #includesGroup(GroupId)} answer "what may this request see"; {@link #canAdminister()}/{@link
 * #canManage(Ownership)} answer "what may this request do" — a distinct question this type used to
 * be asked without a dedicated answer (docs/plans/done/OPS-UX-PLAN.md §1). See their own javadoc
 * for why the two questions diverge for {@link Kind#ASSIGNED_ASSETS}.
 *
 * @param kind           which case this scope is
 * @param groups         the visible group ids (used only when {@code kind} is {@link Kind#GROUPS});
 *                       defensively copied
 * @param assignedAssets the visible asset ids (used only when {@code kind} is
 *                       {@link Kind#ASSIGNED_ASSETS}); defensively copied
 */
public record VisibilityScope(Kind kind, Set<GroupId> groups, Set<AssetId> assignedAssets) {

    /** The three visibility models. */
    public enum Kind {
        /** Sees everything — ADMIN, or auth disabled. */
        UNBOUNDED,
        /** Sees assets owned by any group in {@link #groups()} — MANAGER. */
        GROUPS,
        /** Sees only the assets in {@link #assignedAssets()} — PILOT. */
        ASSIGNED_ASSETS
    }

    public VisibilityScope {
        Objects.requireNonNull(kind, "kind must not be null");
        groups = groups == null ? Set.of() : Set.copyOf(groups);
        assignedAssets = assignedAssets == null ? Set.of() : Set.copyOf(assignedAssets);
    }

    /**
     * A scope that includes every asset (ADMIN / auth-off).
     *
     * @return the unbounded scope
     */
    public static VisibilityScope unbounded() {
        return new VisibilityScope(Kind.UNBOUNDED, Set.of(), Set.of());
    }

    /**
     * A scope limited to assets owned by any of the given groups (MANAGER).
     *
     * @param groups the visible group ids (typically a manager's group subtree); an empty set
     *               includes nothing
     * @return a group-bounded scope
     */
    public static VisibilityScope groups(Set<GroupId> groups) {
        return new VisibilityScope(Kind.GROUPS, groups, Set.of());
    }

    /**
     * A scope limited to explicitly assigned assets (PILOT).
     *
     * @param assignedAssets the visible asset ids; an empty set includes nothing
     * @return an assignment-bounded scope
     */
    public static VisibilityScope assignedAssets(Set<AssetId> assignedAssets) {
        return new VisibilityScope(Kind.ASSIGNED_ASSETS, Set.of(), assignedAssets);
    }

    /**
     * Whether this scope is the unbounded (sees-everything) case.
     *
     * @return {@code true} iff {@link #kind()} is {@link Kind#UNBOUNDED}
     */
    public boolean isUnbounded() {
        return kind == Kind.UNBOUNDED;
    }

    /**
     * Whether this scope includes the asset identified by {@code assetId}/{@code ownership}.
     *
     * <p>Takes the asset's id and ownership rather than a whole {@code Asset} (warehouse) — this is
     * a kernel-only seam every context filters by, and an asset's id and ownership are all
     * {@link #includes(AssetId, Ownership)} has ever used (docs/plans/active/DOMAIN-SEPARATION-W1.md
     * §15, W1.6a).
     *
     * @param assetId   the asset's id
     * @param ownership the asset's ownership
     * @return {@code true} iff this scope may see the asset
     */
    public boolean includes(AssetId assetId, Ownership ownership) {
        Objects.requireNonNull(assetId, "assetId must not be null");
        Objects.requireNonNull(ownership, "ownership must not be null");
        return switch (kind) {
            case UNBOUNDED -> true;
            case GROUPS -> groups.contains(ownership.groupId());
            case ASSIGNED_ASSETS -> assignedAssets.contains(assetId);
        };
    }

    /**
     * Whether the acting user may manage the organization (create/enable users, create groups,
     * and see the management lists at all). Derives management authority directly from the scope's
     * kind, which maps 1:1 to role in this codebase: {@link Kind#UNBOUNDED} is ADMIN and
     * {@link Kind#GROUPS} is MANAGER — both may manage; {@link Kind#ASSIGNED_ASSETS} (a PILOT, or a
     * user with no membership at all) may not (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 — deferred
     * slice-2 cleanup).
     *
     * @return {@code true} iff {@link #kind()} is {@link Kind#UNBOUNDED} or {@link Kind#GROUPS}
     * @deprecated superseded by {@link Authority#mayManageOrg()} (docs/plans/active/AUTH-ROLES-PLAN.md
     *             §3.1/§3.3, wave B1) — this predicate answers only the visibility half of "may
     *             manage the org," which is exactly why it must not be asked directly once a {@code
     *             VIEWER} role can hold a {@link Kind#GROUPS} scope for wide read access (wave B6): a
     *             wall display would pass this check even though it must never create a user. {@code
     *             Authority} requires the {@code MANAGE_ORG} capability <em>in addition to</em> this
     *             method's answer. No behavior change here — every existing caller's answer is
     *             unchanged; new call sites should reach {@code Authority} instead, and existing ones
     *             migrate in wave B6.
     */
    @Deprecated
    public boolean canManageOrg() {
        return kind == Kind.UNBOUNDED || kind == Kind.GROUPS;
    }

    /**
     * Whether this scope includes the given group — the group half of
     * {@link #includes(AssetId, Ownership)}, used by the management gates to check that a
     * grant/parent stays within the acting user's subtree.
     *
     * @param groupId the group to test
     * @return {@code true} for an {@link Kind#UNBOUNDED} scope; for a {@link Kind#GROUPS} scope iff
     *         the id is in {@link #groups()}; always {@code false} for {@link Kind#ASSIGNED_ASSETS}
     */
    public boolean includesGroup(GroupId groupId) {
        Objects.requireNonNull(groupId, "groupId must not be null");
        return switch (kind) {
            case UNBOUNDED -> true;
            case GROUPS -> groups.contains(groupId);
            case ASSIGNED_ASSETS -> false;
        };
    }

    /**
     * Whether the acting user may take a deployment-global action — promote the live CV model,
     * start a training job, or anything else with a blast radius wider than one group's fleet.
     *
     * <p>Exists because {@link #includes(AssetId, Ownership)} and {@link #canManageOrg()} both
     * answer a <em>visibility</em> question ("what may this request see/reach"), and two real call
     * sites (docs/plans/done/OPS-UX-PLAN.md §1, citing docs/conclusions/OPS-UX-REVIEW.md §A1) had
     * been asking {@link #canManageOrg()} an <em>authority</em> question instead — whether the
     * caller may swap the model every stream in the deployment uses, or claim the one training
     * host. {@link Kind#GROUPS} (a MANAGER) is exactly as visible/manageable as {@link
     * Kind#UNBOUNDED} for its own subtree, but has no more authority over the rest of the
     * deployment than {@link Kind#ASSIGNED_ASSETS} does — a group boundary is not a promise of
     * global authority, so only {@link Kind#UNBOUNDED} (ADMIN, or the dev principal when auth is
     * off) may answer yes here.
     *
     * @return {@code true} iff {@link #kind()} is {@link Kind#UNBOUNDED}
     * @deprecated superseded by {@link Authority#mayAdminister()} (docs/plans/active/AUTH-ROLES-PLAN.md
     *             §3.1/§3.3, wave B1), for the same reason as {@link #canManageOrg()}: this predicate
     *             is visibility-shaped only, and {@code Authority} additionally requires the {@code
     *             MANAGE_ORG} capability. No behavior change here; new call sites should reach {@code
     *             Authority} instead, and existing ones migrate in wave B6.
     */
    @Deprecated
    public boolean canAdminister() {
        return kind == Kind.UNBOUNDED;
    }

    /**
     * Whether the acting user may administer (rename, deactivate, delete, reassign the devices of)
     * the asset owned by {@code ownership} — as opposed to merely seeing it.
     *
     * <p>Exists for the same reason as {@link #canAdminister()}: {@link
     * #includes(AssetId, Ownership)} is a visibility filter, and asset lifecycle writes
     * (docs/plans/done/OPS-UX-PLAN.md §1) had been gated on it directly, which conflates "the
     * caller can see this asset" with "the caller may administer it." A PILOT's {@link
     * Kind#ASSIGNED_ASSETS} scope is built so they can see (and fly) exactly the aircraft assigned
     * to them — that is the whole of a pilot's authority, so this predicate is {@code false} for
     * {@link Kind#ASSIGNED_ASSETS} regardless of whether the asset is assigned to them. A MANAGER's
     * {@link Kind#GROUPS} scope, by contrast, already *is* a management subtree, so it grants the
     * same authority over an asset in it that {@link #includes(AssetId, Ownership)} already grants
     * visibility — {@link Kind#GROUPS} is the one case where the two predicates agree.
     *
     * @param ownership the asset's ownership
     * @return {@code true} for {@link Kind#UNBOUNDED}; for {@link Kind#GROUPS} iff {@link
     *         Ownership#groupId()} is in {@link #groups()}; always {@code false} for {@link
     *         Kind#ASSIGNED_ASSETS}
     * @deprecated superseded by {@link Authority#mayManageFleet(Ownership)}
     *             (docs/plans/active/AUTH-ROLES-PLAN.md §3.1/§3.3, wave B1), for the same reason as
     *             {@link #canManageOrg()}: this predicate is visibility-shaped only, and {@code
     *             Authority} additionally requires the {@code MANAGE_FLEET} capability. No behavior
     *             change here; new call sites should reach {@code Authority} instead, and existing
     *             ones migrate in wave B6.
     */
    @Deprecated
    public boolean canManage(Ownership ownership) {
        Objects.requireNonNull(ownership, "ownership must not be null");
        return switch (kind) {
            case UNBOUNDED -> true;
            case GROUPS -> groups.contains(ownership.groupId());
            case ASSIGNED_ASSETS -> false;
        };
    }
}
