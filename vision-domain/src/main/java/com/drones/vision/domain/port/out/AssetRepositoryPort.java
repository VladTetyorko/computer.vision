package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve assets.
 *
 * <p>Shared asset state is reachable only through this port — no use case
 * holds asset state directly — so the backing store can be externalized
 * without any change to core code. {@link #findByDeviceId(DeviceId)} is what
 * lets a device be resolved back to the asset that owns it (e.g. to derive
 * scope for a device-level operation, or to know which asset's usage to
 * update when a device's stream starts/stops).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(Asset)} inserts or updates (upsert by {@link AssetId})
 *       and returns the persisted asset.</li>
 *   <li>{@link #findById(AssetId)} returns {@link Optional#empty()}, never
 *       {@code null}, when no asset with that id exists.</li>
 *   <li>{@link #findAll()} returns a snapshot; the returned list is not a
 *       live view of the store.</li>
 *   <li>{@link #findByDeviceId(DeviceId)} returns the asset that currently
 *       wraps the given device, or {@link Optional#empty()} if the device is
 *       unassigned/unknown. A device belongs to at most one asset.</li>
 *   <li>{@link #deleteById(AssetId)} is idempotent: deleting a non-existent
 *       id is a no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple control-plane
 * operations may read/write assets concurrently, and no caller assumes
 * exclusive access.
 */
public interface AssetRepositoryPort {

    /**
     * Inserts or updates an asset.
     *
     * @param asset the asset to persist
     * @return the persisted asset
     */
    Asset save(Asset asset);

    /**
     * Finds an asset by id.
     *
     * @param id the asset id
     * @return the asset, or {@link Optional#empty()} if none exists
     */
    Optional<Asset> findById(AssetId id);

    /**
     * Lists all assets.
     *
     * @return an immutable snapshot of all assets
     */
    List<Asset> findAll();

    /**
     * Finds the asset that wraps the given device, if any.
     *
     * @param deviceId the device id
     * @return the owning asset, or {@link Optional#empty()} if the device is unassigned/unknown
     */
    Optional<Asset> findByDeviceId(DeviceId deviceId);

    /**
     * Deletes an asset by id. Idempotent.
     *
     * @param id the asset id to delete
     */
    void deleteById(AssetId id);
}
