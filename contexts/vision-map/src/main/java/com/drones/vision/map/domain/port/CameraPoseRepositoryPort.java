package com.drones.vision.map.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.domain.model.CameraPose;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve {@link CameraPose}s (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
 * decision D4) — one row per {@link AssetId}, upsert, the same minimal shape every other repository
 * port in this module uses.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(CameraPose)} upserts by {@link CameraPose#assetId()}: an asset seen before has
 *       its pose replaced in place, a new asset id is added.</li>
 *   <li>{@link #findByAssetId(AssetId)} returns {@link Optional#empty()}, never {@code null}, when
 *       the asset has no pose stored.</li>
 *   <li>{@link #findAll()} returns a snapshot of every stored pose — what {@code
 *       TrackProjectionRunner} (vision-app) iterates each tick to find calibrated assets.</li>
 *   <li>{@link #deleteByAssetId(AssetId)} is idempotent: deleting an asset with no stored pose is a
 *       no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — pose CRUD (control-plane requests) and pose
 * reads (the projection runner's own scheduled cadence) may happen concurrently.
 */
public interface CameraPoseRepositoryPort {

    /**
     * Inserts or updates a camera pose, keyed by {@link CameraPose#assetId()}.
     *
     * @param pose the pose to persist
     * @return the persisted pose
     */
    CameraPose save(CameraPose pose);

    /**
     * Finds the stored pose for an asset.
     *
     * @param assetId the asset id
     * @return the pose, or {@link Optional#empty()} if none is stored
     */
    Optional<CameraPose> findByAssetId(AssetId assetId);

    /**
     * Lists every stored camera pose.
     *
     * @return an immutable snapshot of every pose
     */
    List<CameraPose> findAll();

    /**
     * Deletes an asset's stored pose, if any. Idempotent.
     *
     * @param assetId the asset id whose pose to delete
     */
    void deleteByAssetId(AssetId assetId);
}
