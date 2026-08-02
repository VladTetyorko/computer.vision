package com.drones.vision.api.dto;

import com.drones.vision.application.category.CategoryCounts;

/**
 * One row of {@code GET /api/fleet/summary}'s {@code categories} array (docs/MVP3-PLAN.md C-a) —
 * per-category asset counts, lifecycle state crossed with whether the asset is currently streaming.
 * Every field is always present (no {@code @JsonInclude(NON_NULL)} needed: nothing here is
 * nullable).
 *
 * @param categoryId   the category slug
 * @param categoryName human-readable category name
 * @param total        every asset in this category in scope for the summary
 * @param active       {@code total}'s subset in lifecycle {@code ACTIVE}
 * @param deactivated  {@code total}'s subset in lifecycle {@code DEACTIVATED}
 * @param deleted      {@code total}'s subset in lifecycle {@code DELETED} (only non-zero when the
 *                     request asked for {@code includeArchived=true})
 * @param streaming    {@code total}'s subset currently streaming
 */
public record CategoryCountsResponse(String categoryId, String categoryName, int total, int active,
                                      int deactivated, int deleted, int streaming) {

    /**
     * Maps a domain {@link CategoryCounts} read model to its wire representation.
     *
     * @param counts the counts to map
     * @return the response body element for {@code counts}
     */
    public static CategoryCountsResponse from(CategoryCounts counts) {
        return new CategoryCountsResponse(counts.categoryId().slug(), counts.categoryName(), counts.total(),
                counts.active(), counts.deactivated(), counts.deleted(), counts.streaming());
    }
}
