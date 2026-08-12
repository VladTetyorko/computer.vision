package com.drones.vision.identity.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UserId;

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
 *   <li>{@link #assign(UserId, AssetId)} is an <b>idempotent upsert</b>: assigning an already-assigned
 *       (pilot, asset) pair is a no-op, not an error, and never creates a duplicate link.</li>
 *   <li>{@link #unassign(UserId, AssetId)} is likewise <b>idempotent</b>: removing a link that is not
 *       present is a no-op, not an error.</li>
 *   <li>{@link #assetsForPilot(UserId)} / {@link #pilotsForAsset(AssetId)} return an immutable
 *       snapshot; an empty set (never {@code null}) means "no links," never distinguishing an
 *       unknown pilot/asset from a known one with no links.</li>
 *   <li>{@link #isAssigned(UserId, AssetId)} is the cheap presence check.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — assignments are read and written from any
 * control-plane call, and no caller assumes exclusive access.
 */
public interface AssignmentRepositoryPort {

    /**
     * Links a pilot to an asset, idempotently.
     *
     * @param pilot the pilot to assign
     * @param asset the asset to assign them to
     */
    void assign(UserId pilot, AssetId asset);

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
}
