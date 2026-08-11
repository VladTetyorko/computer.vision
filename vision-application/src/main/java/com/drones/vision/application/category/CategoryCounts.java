package com.drones.vision.application.category;

import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.application.asset.AssetSummary;
import com.drones.vision.application.fleet.FleetSummaryService;

/**
 * Per-category asset counts for one row of {@code GET /api/fleet/summary}'s "warehouse readiness"
 * breakdown (docs/plans/done/MVP3-PLAN.md C-a) — lifecycle state crossed with whether the asset is currently
 * streaming.
 *
 * @param categoryId   the category
 * @param categoryName human-readable name (mirrors {@link AssetSummary#categoryName()})
 * @param total        every asset in this category in scope for this summary (see {@link
 *                     FleetSummaryService#summary(boolean)}'s {@code includeArchived})
 * @param active       {@code total}'s subset in {@link com.drones.vision.domain.model.LifecycleState#ACTIVE}
 * @param deactivated  {@code total}'s subset in {@code DEACTIVATED}
 * @param deleted      {@code total}'s subset in {@code DELETED} (only non-zero when the summary
 *                     included archived assets)
 * @param streaming    {@code total}'s subset currently streaming — always &le; {@code active},
 *                     since only an in-service asset can ever have an active stream
 */
public record CategoryCounts(CategoryId categoryId, String categoryName, int total, int active, int deactivated,
                              int deleted, int streaming) {

    public CategoryCounts {
        if (categoryId == null) {
            throw new IllegalArgumentException("CategoryCounts categoryId must not be null");
        }
        if (categoryName == null || categoryName.isBlank()) {
            throw new IllegalArgumentException("CategoryCounts categoryName must not be blank");
        }
        if (total < 0 || active < 0 || deactivated < 0 || deleted < 0 || streaming < 0) {
            throw new IllegalArgumentException("CategoryCounts counts must not be negative");
        }
    }
}
