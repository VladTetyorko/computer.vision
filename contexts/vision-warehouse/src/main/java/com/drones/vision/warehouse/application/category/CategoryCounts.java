package com.drones.vision.warehouse.application.category;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.application.fleet.FleetSummaryService;

/**
 * Per-category asset counts for one row of {@code GET /api/fleet/summary}'s "warehouse readiness"
 * breakdown (docs/plans/done/MVP3-PLAN.md C-a) — lifecycle state crossed with whether the asset is currently
 * streaming.
 *
 * @param categoryId   the category
 * @param categoryName human-readable name (mirrors {@link AssetSummary#categoryName()})
 * @param total        every asset in this category in scope for this summary (see {@link
 *                     FleetSummaryService#summary(boolean)}'s {@code includeArchived})
 * @param active       {@code total}'s subset in {@link com.drones.vision.kernel.LifecycleState#ACTIVE}
 * @param deactivated  {@code total}'s subset in {@code DEACTIVATED}
 * @param deleted      {@code total}'s subset in {@code DELETED} (only non-zero when the summary
 *                     included archived assets)
 * @param streaming    {@code total}'s subset currently streaming — always &le; {@code active},
 *                     since only an in-service asset can ever have an active stream
 * @param inStock      {@code total}'s subset whose effective inventory state is {@code IN_STOCK}
 * @param issued       {@code total}'s subset whose effective inventory state is {@code ISSUED}
 * @param inField      {@code total}'s subset whose effective inventory state is {@code IN_FIELD}
 * @param maintenance  {@code total}'s subset whose effective inventory state is {@code MAINTENANCE}
 * @param retired      {@code total}'s subset whose effective inventory state is {@code RETIRED}
 */
public record CategoryCounts(CategoryId categoryId, String categoryName, int total, int active, int deactivated,
                              int deleted, int streaming, int inStock, int issued, int inField, int maintenance,
                              int retired) {

    public CategoryCounts {
        if (categoryId == null) {
            throw new IllegalArgumentException("CategoryCounts categoryId must not be null");
        }
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("CategoryCounts categoryName must not be blank");
        }
        if (total < 0 || active < 0 || deactivated < 0 || deleted < 0 || streaming < 0
                || inStock < 0 || issued < 0 || inField < 0 || maintenance < 0 || retired < 0) {
            throw new IllegalArgumentException("CategoryCounts counts must not be negative");
        }
    }
}
