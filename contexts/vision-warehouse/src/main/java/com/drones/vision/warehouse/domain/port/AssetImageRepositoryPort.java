package com.drones.vision.warehouse.domain.port;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetImage;

import java.util.Optional;

/**
 * Driven port: stores and retrieves one {@link AssetImage} per {@link AssetId} — the asset's
 * user-facing photo (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3, UX-DESIGN.md §5.1). No history: {@link
 * #save} replaces whatever was stored for that asset wholesale.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #save} is an upsert — a second call for the same {@link AssetId} replaces the
 *       first, it never adds a second image.</li>
 *   <li>{@link #findByAssetId} returns {@link Optional#empty()} for an asset that has never had
 *       an image saved (or whose image was since deleted) — never an error, and never distinguishes
 *       that case from "this asset id doesn't even exist" (this port has no dependency on {@code
 *       AssetRepositoryPort} — see below).</li>
 *   <li>{@link #existsByAssetId} answers the same question as {@code findByAssetId(id).isPresent()}
 *       without necessarily loading the image bytes — the cheap check a fleet/asset list uses to
 *       populate a {@code hasImage} flag per row without paying for every row's full payload.</li>
 *   <li>{@link #deleteByAssetId} is idempotent — deleting for an asset with no stored image is a
 *       no-op, not an error.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method must be safe to call concurrently for different {@link AssetId}s.
 *
 * <p>Deliberately carries no asset-existence check of its own — this port stores bytes keyed by
 * an id, nothing more, mirroring this codebase's existing "no referential integrity between
 * repositories" convention (adapter-persistence/MODULE.md's Conventions: none of {@code
 * asset_usages}/{@code telemetry_samples}/{@code detection_results} has a foreign key back to
 * {@code assets} either). An image attached to an id nothing else knows about is harmless — it
 * simply never surfaces, since only known/listed assets are ever asked about.
 */
public interface AssetImageRepositoryPort {

    /**
     * Stores {@code image} for {@code assetId}, replacing whatever was stored before.
     *
     * @param assetId the asset the image belongs to
     * @param image   the image to store
     */
    void save(AssetId assetId, AssetImage image);

    /**
     * @param assetId the asset to look up
     * @return the asset's stored image, or {@link Optional#empty()} if none
     */
    Optional<AssetImage> findByAssetId(AssetId assetId);

    /**
     * @param assetId the asset to check
     * @return {@code true} if an image is stored for {@code assetId}
     */
    boolean existsByAssetId(AssetId assetId);

    /**
     * Removes {@code assetId}'s stored image, if any. Idempotent.
     *
     * @param assetId the asset whose image to remove
     */
    void deleteByAssetId(AssetId assetId);
}
