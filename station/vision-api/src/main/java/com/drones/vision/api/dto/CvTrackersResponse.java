package com.drones.vision.api.dto;

import java.util.List;

/**
 * Response body for {@code GET /api/cv/trackers} (docs/plans/done/TRACKING-PLAN.md &sect;4.F's frozen wire
 * contract) — the same wrapped-list shape {@link CvModelsResponse} already set the precedent for.
 *
 * @param trackers the tracker-engine roster, in display order; never empty in practice (the roster
 *                 bean always carries at least the built-ins)
 */
public record CvTrackersResponse(List<CvTrackerResponse> trackers) {

    public CvTrackersResponse {
        trackers = List.copyOf(trackers);
    }
}
