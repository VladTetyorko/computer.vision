package com.drones.vision.identity.application.scope;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.identity.domain.model.Role;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a request may see: the resolved boundary of one user's visibility (docs/plans/done/U-SCOPE-PLAN.md,
 * U-e slice 2, feature 1). Computed once per request from the acting user by {@link ScopeResolver}
 * and threaded into the user-facing read paths, which filter by {@link #includes(Asset)}.
 *
 * <p>Three cases, one per privilege model:
 * <ul>
 *   <li>{@link #unbounded()} — ADMIN, and the dev principal when {@code vision.auth.enabled=false}.
 *       {@link #includes(Asset)} is always {@code true}, so the default-off build behaves exactly as
 *       it does today (this is the slice's guardrail — a scoped read given an unbounded scope
 *       returns precisely the unscoped result).</li>
 *   <li>{@link #groups(Set)} — MANAGER. {@link #includes(Asset)} is {@code true} iff the asset's
 *       {@code ownership().groupId()} is in the scope's group set (the manager's group subtree, which
 *       {@link ScopeResolver} expands from the group tree).</li>
 *   <li>{@link #assignedAssets(Set)} — PILOT. {@link #includes(Asset)} is {@code true} iff the
 *       asset's id is in the scope's assigned-asset set.</li>
 * </ul>
 *
 * <p>An empty {@code groups} or {@code assignedAssets} scope includes nothing — a user with no
 * memberships and no assignments sees nothing until assigned (documented on {@link ScopeResolver}).
 * Both collections are defensively copied to immutable sets in the compact constructor.
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
     * Whether this scope includes the given asset.
     *
     * @param asset the asset to test
     * @return {@code true} iff this scope may see the asset
     */
    public boolean includes(Asset asset) {
        Objects.requireNonNull(asset, "asset must not be null");
        return switch (kind) {
            case UNBOUNDED -> true;
            case GROUPS -> groups.contains(asset.ownership().groupId());
            case ASSIGNED_ASSETS -> assignedAssets.contains(asset.id());
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
     */
    public boolean canManageOrg() {
        return kind == Kind.UNBOUNDED || kind == Kind.GROUPS;
    }

    /**
     * Whether this scope includes the given group — the group half of {@link #includes(Asset)},
     * used by the management gates to check that a grant/parent stays within the acting user's
     * subtree.
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
     * The highest {@link Role} the acting user may grant to someone else — the ≤-own-scope grant
     * ceiling. An {@link Kind#UNBOUNDED} scope (ADMIN) may grant {@link Role#ADMIN}; a
     * {@link Kind#GROUPS} scope (MANAGER) may grant at most {@link Role#MANAGER}; a
     * {@link Kind#ASSIGNED_ASSETS} scope may grant nothing.
     *
     * <p>Compared against a candidate role by {@link Role}'s ordinal ordering (ADMIN highest).
     *
     * @return the maximum grantable role, or {@link Optional#empty()} if this scope may grant none
     */
    public Optional<Role> maxGrantableRole() {
        return switch (kind) {
            case UNBOUNDED -> Optional.of(Role.ADMIN);
            case GROUPS -> Optional.of(Role.MANAGER);
            case ASSIGNED_ASSETS -> Optional.empty();
        };
    }
}
