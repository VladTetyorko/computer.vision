package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve maintenance records.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(MaintenanceRecord)} inserts or updates (upsert by {@link MaintenanceId}) and
 *       returns the persisted record.</li>
 *   <li>{@link #findById(MaintenanceId)} returns {@link Optional#empty()}, never {@code null},
 *       when no record with that id exists.</li>
 *   <li>{@link #findByAsset(AssetId)} returns every record ever opened against the asset,
 *       open or closed — the full maintenance history shown on an asset's page.</li>
 *   <li>{@link #findOpenByAsset(AssetId)} returns only the currently-open records for the asset;
 *       an asset may have more than one open at once (e.g. a grounding and an unrelated repair
 *       note).</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use — multiple control-plane operations may
 * read/write maintenance records concurrently, and no caller assumes exclusive access.
 */
public interface MaintenanceRepositoryPort {

    /**
     * Inserts or updates a maintenance record.
     *
     * @param record the record to persist
     * @return the persisted record
     */
    MaintenanceRecord save(MaintenanceRecord record);

    /**
     * Finds a maintenance record by id.
     *
     * @param id the record id
     * @return the record, or {@link Optional#empty()} if none exists
     */
    Optional<MaintenanceRecord> findById(MaintenanceId id);

    /**
     * Lists every maintenance record ever opened against an asset, open or closed.
     *
     * @param assetId the asset id
     * @return an immutable snapshot of the asset's maintenance history
     */
    List<MaintenanceRecord> findByAsset(AssetId assetId);

    /**
     * Lists the currently-open maintenance records for an asset.
     *
     * @param assetId the asset id
     * @return an immutable snapshot of the asset's open records; empty if none are open
     */
    List<MaintenanceRecord> findOpenByAsset(AssetId assetId);
}
