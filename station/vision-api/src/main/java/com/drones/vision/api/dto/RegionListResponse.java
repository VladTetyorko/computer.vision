package com.drones.vision.api.dto;

import java.util.List;

/** Response body for {@code GET /api/geo/regions} (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.3). */
public record RegionListResponse(List<RegionResponse> regions) {
}
