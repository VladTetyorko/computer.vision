package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/coverage} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2's
 * frozen wire contract) — mirrors {@link CvModelsResponse}'s wrapped-list shape.
 *
 * @param rows one row per asset the caller's scope may see
 */
public record CvCoverageResponse(List<CvCoverageRowResponse> rows) {

    public CvCoverageResponse {
        rows = List.copyOf(rows);
    }
}
