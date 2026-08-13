package com.drones.vision.api.dto;

import java.util.List;

/**
 * Wrapper body for {@code GET /api/datasets} (docs/plans/done/CV-TRAINING-PLAN.md §3's frozen wire contract)
 * — mirrors {@link CvModelsResponse}'s wrapped-list shape rather than a bare JSON array, per the
 * plan's own distinct {@code DatasetsResponse} type name.
 *
 * @param datasets a scope-filtered snapshot, in {@link com.drones.vision.learning.application.DatasetService#list}'s
 *                 own order
 */
public record DatasetsResponse(List<DatasetResponse> datasets) {
}
