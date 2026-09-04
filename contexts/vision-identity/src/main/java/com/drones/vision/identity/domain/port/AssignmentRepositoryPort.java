package com.drones.vision.identity.domain.port;

import com.drones.vision.identity.domain.model.Assignment;
import com.drones.vision.identity.domain.model.AssignmentRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Driven port: the pilot&rarr;asset assignment join (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 2).
 *
 * <p>An assignment is a plain many-to-many link between a {@link UserId pilot} and an
 * {@link AssetId asset} — "this pilot flies that aircraft." It is deliberately kept as its own
 * join, independent of both the {@code User} and {@code Asset} aggregates, because a pilot's roster
 * changes far more often than either identity does; storing it on the asset (or the user) would
 * force a full aggregate save on every roster tweak.
 *
 * <p>This port has no referential integrity to {@code UserRepositoryPort}/{@code
 * AssetRepositoryPort} — the same "no cross-repository foreign keys" convention every other port in
 * this package follows. Existence of the pilot/asset is the caller's (application layer's) concern.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #assign(UserId, AssetId, AssignmentRole)} is an <b>idempotent upsert</b>: assigning an
 *       already-assigned (pilot, asset) pair is a no-op on the link's existence — it always writes
 *       {@code role}, so re-assigning an existing link with a different {@link AssignmentRole} is how
 *       a seat changes (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B1) — and never creates a
 *       duplicate link.</li>
 *   <li>{@link #unassign(UserId, AssetId)} is likewise <b>idempotent</b>: removing a link that is not
 *       present is a no-op, not an error.</li>
 *   <li>{@link #assetsForPilot(UserId)} / {@link #pilotsForAsset(AssetId)} return an immutable
 *       snapshot; an empty set (never {@code null}) means "no links," never distinguishing an
 *       unknown pilot/asset from a known one with no links. Both include a link regardless of its
 *       {@link AssignmentRole} — visibility follows from being assigned at all; only the verb a seat
 *       grants differs (docs/plans/active/AUTH-ROLES-PLAN.md §3.4).</li>
 *   <li>{@link #isAssigned(UserId, AssetId)} is the cheap presence check, role-independent.</li>
 *   <li>{@link #roleFor(UserId, AssetId)} is the seat lookup: {@link Optional#empty()} iff no link
 *       exists, never used to mean "assigned with an unknown role" — every stored link has exactly
 *       one {@link AssignmentRole}.</li>
 *   <li>{@link #assignmentsForAsset(AssetId)} is the roster for one asset — every current pilot/crew
 *       link on it, with each one's seat.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — assignments are read and written from any
 * control-plane call, and no caller assumes exclusive access.
 */
public interface AssignmentRepositoryPort {

    /**
     * Links a pilot to an asset with a seat, idempotently — re-assigning an existing link with a
     * different {@link AssignmentRole} changes the seat rather than creating a second link
     * (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B1).
     *
     * @param pilot the pilot to assign
     * @param asset the asset to assign them to
     * @param role  the seat this link grants
     */
    void assign(UserId pilot, AssetId asset, AssignmentRole role);

    /**
     * Removes a pilot&rarr;asset link, idempotently.
     *
     * @param pilot the pilot to unassign
     * @param asset the asset to unassign them from
     */
    void unassign(UserId pilot, AssetId asset);

    /**
     * The assets a pilot is assigned to.
     *
     * @param pilot the pilot
     * @return an immutable snapshot of assigned asset ids, empty if none
     */
    Set<AssetId> assetsForPilot(UserId pilot);

    /**
     * The pilots assigned to an asset.
     *
     * @param asset the asset
     * @return an immutable snapshot of assigned pilot ids, empty if none
     */
    Set<UserId> pilotsForAsset(AssetId asset);

    /**
     * Whether a pilot is assigned to an asset.
     *
     * @param pilot the pilot
     * @param asset the asset
     * @return {@code true} iff the link exists
     */
    boolean isAssigned(UserId pilot, AssetId asset);

    /**
     * The seat a pilot holds on an asset, if assigned at all — the CREW-CONTROL-PLAN.md IC-2 answer
     * (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B1).
     *
     * @param pilot the pilot
     * @param asset the asset
     * @return the held {@link AssignmentRole}, or {@link Optional#empty()} iff no link exists
     */
    Optional<AssignmentRole> roleFor(UserId pilot, AssetId asset);

    /**
     * The full pilot/crew roster for one asset (docs/plans/active/AUTH-ROLES-PLAN.md §3.4, wave B1).
     *
     * @param asset the asset
     * @return an immutable snapshot of every current assignment on it, empty if none
     */
    List<Assignment> assignmentsForAsset(AssetId asset);
}
