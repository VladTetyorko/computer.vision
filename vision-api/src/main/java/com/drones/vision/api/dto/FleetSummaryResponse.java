package com.drones.vision.api.dto;

import com.drones.vision.warehouse.application.fleet.FleetSummary;

import java.util.List;

/**
 * Body of {@code GET /api/fleet/summary} (docs/plans/done/MVP3-PLAN.md C-a) — the manager dashboard's one
 * aggregated poll: per-category counts plus a capped, per-asset attention list. Every field is
 * always present (no {@code @JsonInclude(NON_NULL)} needed).
 *
 * @param categories  per-category counts, one row per category actually present among the assets
 *                    in scope
 * @param assets      per-asset attention facts, capped (see {@code DefaultFleetSummaryService}'s
 *                    {@code MAX_ASSETS_IN_SUMMARY}) and sorted by display name
 * @param totalAssets the true count of assets in scope, independent of {@code assets}' own cap — a
 *                    caller can tell the list was truncated when {@code assets.size() <
 *                    totalAssets}
 */
public record FleetSummaryResponse(List<CategoryCountsResponse> categories, List<AssetAttentionResponse> assets,
                                    int totalAssets) {

    /**
     * Maps a domain {@link FleetSummary} read model to its wire representation.
     *
     * @param summary the summary to map
     * @return the response body for {@code summary}
     */
    public static FleetSummaryResponse from(FleetSummary summary) {
        return new FleetSummaryResponse(
                summary.categories().stream().map(CategoryCountsResponse::from).toList(),
                summary.assets().stream().map(AssetAttentionResponse::from).toList(),
                summary.totalAssets());
    }
}
