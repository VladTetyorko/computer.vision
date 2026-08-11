package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.UsageId;

import java.util.List;
import java.util.Optional;

/**
 * Driven port: persist and retrieve asset usage history ("flights"/sessions).
 *
 * <p>A usage row holds a cheap summary (start/last position, sample count)
 * for list rendering; the underlying telemetry trail is stored separately
 * via {@code TelemetryRepositoryPort} and fetched only on demand. Usages are
 * append-only and time-keyed (TimescaleDB-ready): {@link #save(AssetUsage)}
 * upserts by id, so the same usage can be updated in place as it progresses
 * (position/sample-count updates) and finally closed, rather than requiring
 * a new row per update.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save(AssetUsage)} inserts or updates (upsert by {@link
 *       UsageId}) and returns the persisted usage.</li>
 *   <li>{@link #findById(UsageId)} returns {@link Optional#empty()}, never
 *       {@code null}, when no usage with that id exists.</li>
 *   <li>{@link #findRecentByAsset(AssetId, int)} returns the most recent
 *       usages for the asset, newest first, bounded to at most {@code
 *       limit}; a snapshot, not a live view.</li>
 *   <li>{@link #findRecent(int)} is {@link #findRecentByAsset(AssetId, int)}'s
 *       fleet-wide counterpart (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8): the
 *       most recent usages across every asset, newest first, bounded to at
 *       most {@code limit}; a snapshot, not a live view.</li>
 *   <li>{@link #findOpenByAsset(AssetId)} returns the asset's currently open
 *       usage ({@code endedAt == null}), if any. At most one usage per asset
 *       is open at a time.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Implementations must be safe for concurrent use: {@code save} may be
 * called frequently while a usage is open (e.g. once per telemetry sample)
 * from the collaborator driving the usage lifecycle, while reads may happen
 * concurrently from control-plane queries.
 */
public interface AssetUsageRepositoryPort {

    /**
     * Inserts or updates a usage.
     *
     * @param usage the usage to persist
     * @return the persisted usage
     */
    AssetUsage save(AssetUsage usage);

    /**
     * Finds a usage by id.
     *
     * @param id the usage id
     * @return the usage, or {@link Optional#empty()} if none exists
     */
    Optional<AssetUsage> findById(UsageId id);

    /**
     * Lists the most recent usages for an asset, newest first.
     *
     * @param assetId the asset id
     * @param limit   maximum number of usages to return; must be positive
     * @return an immutable snapshot of the most recent usages, newest first
     */
    List<AssetUsage> findRecentByAsset(AssetId assetId, int limit);

    /**
     * Lists the most recent usages across every asset, newest first — the fleet-wide "replay
     * library" query (docs/plans/done/NAV-IA-REDESIGN-PLAN.md Wave 4, F8), {@link #findRecentByAsset(AssetId,
     * int)}'s cross-asset counterpart.
     *
     * @param limit maximum number of usages to return; must be positive
     * @return an immutable snapshot of the most recent usages across every asset, newest first
     */
    List<AssetUsage> findRecent(int limit);

    /**
     * Finds the asset's currently open usage, if any.
     *
     * @param assetId the asset id
     * @return the open usage, or {@link Optional#empty()} if none is open
     */
    Optional<AssetUsage> findOpenByAsset(AssetId assetId);
}
