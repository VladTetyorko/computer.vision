package com.drones.vision.application.asset;

import com.drones.vision.kernel.AssetId;

/**
 * Aggregates one asset's flight-utilization history into {@link AssetStats} — the read side
 * behind {@code GET /api/assets/{id}/stats} (docs/plans/done/ASSET-MANAGER-PAGE-PLAN.md, Wave A), the
 * manager page's KPI tile row.
 *
 * <p>The one implementation, {@link DefaultAssetStatsService}, deliberately never checks whether
 * the asset itself exists — see {@link #statsFor(AssetId)}'s own javadoc.
 */
public interface AssetStatsService {

    /**
     * Aggregates {@code assetId}'s usage history into flight stats.
     *
     * <p>Never throws for an asset with zero usages (including a genuinely unknown asset id) —
     * it simply reports zero flights, all-{@code null} optionals. {@code vision-api}'s controller
     * is responsible for 404ing an unknown asset before calling this method, since this service
     * has no asset-existence port of its own to check against and aggregating "whatever usages
     * exist" is honestly correct either way.
     *
     * @param assetId the asset to aggregate stats for
     * @return the asset's flight stats
     */
    AssetStats statsFor(AssetId assetId);
}
