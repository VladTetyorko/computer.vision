package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.FixedCameraGeoProperties;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy.Viewer;
import com.drones.vision.map.application.track.ProjectedTrackView;
import com.drones.vision.map.application.track.TrackProjectionService;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.ProjectedTrack;
import com.drones.vision.map.domain.model.TrackPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link MapTracksController} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5's
 * frozen wire contract, Wave G4) — {@code GET /api/map/tracks}'s flag-off {@code 409} and the
 * viewer-scoped read that {@link LiveMapScopingTest} proves end to end over the live SSE topic.
 */
class MapTracksControllerTest {

    private TrackProjectionService trackProjectionService;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        trackProjectionService = mock(TrackProjectionService.class);
        mockMvc = mockMvcFor(new FixedCameraGeoProperties(true, 25.0));
    }

    private MockMvc mockMvcFor(FixedCameraGeoProperties properties) {
        return MockMvcBuilders
                .standaloneSetup(new MapTracksController(trackProjectionService, currentUser, properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static ProjectedTrackView view(AssetId assetId, LayerId layerId) {
        ProjectedTrack track = new ProjectedTrack(assetId, 3L, "car", layerId, new GeoPosition(50.45, 30.52, null),
                120.0, 15.0, Instant.parse("2026-08-17T10:00:00Z"));
        TrackPoint point = new TrackPoint(assetId, 3L, "car", layerId, new GeoPosition(50.44, 30.51, null), 20.0,
                Instant.parse("2026-08-17T09:59:00Z"));
        return new ProjectedTrackView(track, List.of(point));
    }

    @Test
    void listReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(new FixedCameraGeoProperties(false, 25.0));

        disabledMvc.perform(get("/api/map/tracks"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message")
                        .value("fixed-camera geolocation is disabled (vision.geo.fixed-camera.enabled)"));

        verifyNoInteractions(trackProjectionService);
    }

    @Test
    void listReturns200WithEveryVisibleTrackIncludingItsTrail() throws Exception {
        AssetId assetId = AssetId.random();
        LayerId layerId = LayerId.random();
        when(trackProjectionService.list(any())).thenReturn(List.of(view(assetId, layerId)));

        mockMvc.perform(get("/api/map/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks", hasSize(1)))
                .andExpect(jsonPath("$.tracks[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.tracks[0].trackId").value(3))
                .andExpect(jsonPath("$.tracks[0].label").value("car"))
                .andExpect(jsonPath("$.tracks[0].layerId").value(layerId.value().toString()))
                .andExpect(jsonPath("$.tracks[0].latitude").value(50.45))
                .andExpect(jsonPath("$.tracks[0].longitude").value(30.52))
                .andExpect(jsonPath("$.tracks[0].rangeMeters").value(120.0))
                .andExpect(jsonPath("$.tracks[0].errorRadiusMeters").value(15.0))
                .andExpect(jsonPath("$.tracks[0].trail", hasSize(1)))
                .andExpect(jsonPath("$.tracks[0].trail[0].latitude").value(50.44))
                .andExpect(jsonPath("$.tracks[0].trail[0].longitude").value(30.51))
                .andExpect(jsonPath("$.tracks[0].trail[0].at").value("2026-08-17T09:59:00Z"))
                .andExpect(jsonPath("$.tracks[0].trail[0].errorRadiusMeters").doesNotExist());
    }

    @Test
    void listReturns200WithAnEmptyArrayWhenNothingIsVisible() throws Exception {
        when(trackProjectionService.list(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/map/tracks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tracks", hasSize(0)));
    }

    @Test
    void listThreadsTheCallersViewerIntoTheService() throws Exception {
        when(trackProjectionService.list(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/map/tracks")).andExpect(status().isOk());

        ArgumentCaptor<Viewer> viewer = ArgumentCaptor.forClass(Viewer.class);
        verify(trackProjectionService).list(viewer.capture());
        assertEquals(ownerId, viewer.getValue().userId());
        assertEquals(currentUser.viewer(), viewer.getValue());
    }
}
