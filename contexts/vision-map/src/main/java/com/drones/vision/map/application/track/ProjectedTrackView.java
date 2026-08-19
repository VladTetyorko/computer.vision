package com.drones.vision.map.application.track;

import com.drones.vision.map.domain.model.ProjectedTrack;
import com.drones.vision.map.domain.model.TrackPoint;

import java.util.List;
import java.util.Objects;

/**
 * One row of {@link TrackProjectionService#list} — a live track plus its decimated trail, oldest to
 * newest, exactly the shape {@code GET /api/map/tracks} needs (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md §5's {@code ProjectedTrackResponse}, trail included) — the two-source
 * read this endpoint rebuilds after a reload (D3): the live picture from memory, the trail from
 * {@code TrackTrailRepositoryPort}.
 *
 * @param track the current live fix
 * @param trail every stored trail point for this track, oldest first (already decimated at write
 *              time — no further thinning here)
 */
public record ProjectedTrackView(ProjectedTrack track, List<TrackPoint> trail) {

    public ProjectedTrackView {
        Objects.requireNonNull(track, "track must not be null");
        trail = trail == null ? List.of() : List.copyOf(trail);
    }
}
