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
 * #includesGroup(GroupId)} answer "what may this request see"; {@link Authority#mayAdminister()}/
 * {@link Authority#mayManageFleet(Ownership)} answer "what may this request do" — a distinct
 * question this type used to be asked directly, without a dedicated answer
 * (docs/plans/done/OPS-UX-PLAN.md §1), until {@code canAdminister()}/{@code canManageOrg()}/{@code
 * canManage(Ownership)} were deleted in favor of {@link Authority} (docs/plans/active/AUTH-ROLES-PLAN.md
 * wave B6, once every caller had migrated). See {@link Authority}'s own javadoc for why the two
 * questions diverge for {@link Kind#ASSIGNED_ASSETS}.
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
}
