package com.drones.vision.api.dto;

import java.util.List;

/**
 * The body of {@code GET /api/map/tracks} (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5) — every
 * live track on a layer the caller may view (D10), each with its stored trail.
 *
 * @param tracks the visible tracks
 */
public record MapTracksResponse(List<ProjectedTrackResponse> tracks) {
}
