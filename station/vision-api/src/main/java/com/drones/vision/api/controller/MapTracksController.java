package com.drones.vision.api.controller;

import com.drones.vision.api.dto.MapTracksResponse;
import com.drones.vision.api.dto.ProjectedTrackResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.track.TrackProjectionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Read-only view of live projected tracks (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5/D3) —
 * {@code GET /api/map/tracks}. What a page reload rebuilds the picture from; live deltas ride the
 * existing {@code map} SSE topic instead (see {@code MapEventPayload}'s {@code track} field).
 *
 * <h2>Flag gate (D8)</h2>
 * With {@code vision.geo.fixed-camera.enabled=false} (the default), {@link #list} throws {@link
 * IllegalStateException}, mapped by {@code ApiExceptionHandler} to the frozen §5 {@code 409} — see
 * {@link FixedCameraGeoProperties#requireEnabled()}.
 *
 * <h2>Visibility (D10)</h2>
 * Scoped entirely by the target layer: {@link TrackProjectionService#list(Viewer)} returns only
 * tracks on a layer {@link CurrentUser#viewer()} may {@code canView} — the same {@code
 * MapAccessPolicy} predicate {@link MapMarksController#list} already uses, never {@code
 * VisibilityScope}.
 */
@RestController
public class MapTracksController {

    private final TrackProjectionService trackProjectionService;
    private final CurrentUser currentUser;
    private final FixedCameraGeoProperties properties;

    public MapTracksController(TrackProjectionService trackProjectionService, CurrentUser currentUser,
                                FixedCameraGeoProperties properties) {
        this.trackProjectionService =
                Objects.requireNonNull(trackProjectionService, "trackProjectionService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    /**
     * Lists every live track on a layer the caller may view, each with its stored trail.
     *
     * @return the visible tracks
     */
    @GetMapping("/api/map/tracks")
    public MapTracksResponse list() {
        properties.requireEnabled();
        Viewer viewer = currentUser.viewer();
        return new MapTracksResponse(
                trackProjectionService.list(viewer).stream().map(ProjectedTrackResponse::from).toList());
    }
}
