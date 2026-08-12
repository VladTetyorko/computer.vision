package com.drones.vision.warehouse.application.fleet;

import java.util.List;
import com.drones.vision.warehouse.application.asset.AssetAttention;
import com.drones.vision.warehouse.application.category.CategoryCounts;

/**
 * The read model behind {@code GET /api/fleet/summary} (docs/plans/done/MVP3-PLAN.md C-a): everything the
 * manager dashboard needs to answer "who needs attention right now" in one response — per-category
 * counts plus a bounded, per-asset attention row for each asset in scope.
 *
 * @param categories  per-category counts (docs/plans/done/MVP3-PLAN.md C-a's "lifecycle &times; streaming
 *                    state"), one row per category actually present among the assets this summary
 *                    covers; defensively copied
 * @param assets      per-asset attention facts, capped at {@link
 *                    DefaultFleetSummaryService#MAX_ASSETS_IN_SUMMARY} and sorted by display name
 *                    (see {@link DefaultFleetSummaryService} for why); defensively copied
 * @param totalAssets the true count of assets in scope, independent of {@code assets}' own cap — a
 *                    caller can tell the list was truncated when {@code assets.size() <
 *                    totalAssets}
 */
public record FleetSummary(List<CategoryCounts> categories, List<AssetAttention> assets, int totalAssets) {

    public FleetSummary {
        if (categories == null) {
            throw new IllegalArgumentException("FleetSummary categories must not be null");
        }
        if (assets == null) {
            throw new IllegalArgumentException("FleetSummary assets must not be null");
        }
        if (totalAssets < 0) {
            throw new IllegalArgumentException("FleetSummary totalAssets must not be negative");
        }
        categories = List.copyOf(categories);
        assets = List.copyOf(assets);
    }
}
