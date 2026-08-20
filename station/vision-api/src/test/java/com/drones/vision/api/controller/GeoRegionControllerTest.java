package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.perception.application.geo.ReferenceRegionService;
import com.drones.vision.perception.domain.model.IngestProgress;
import com.drones.vision.perception.domain.model.IngestState;
import com.drones.vision.perception.domain.model.RegionBounds;
import com.drones.vision.perception.domain.model.RegionIngestSpec;
import com.drones.vision.perception.domain.model.RegionStatus;
import com.drones.vision.perception.domain.model.ReferenceRegion;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link GeoRegionController} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3, H5)
 * — the D9 flag-off {@code 409} on all four routes, {@code POST}/{@code DELETE}'s {@code
 * canAdminister} gate, and the plain read-through shape of {@code GET}.
 */
class GeoRegionControllerTest {

    private static final String REGION_BODY = """
            {"name":"kyiv-pozniaky","north":50.46,"south":50.44,"east":30.53,"west":30.51,"zoom":17}""";

    private ReferenceRegionService referenceRegionService;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        referenceRegionService = mock(ReferenceRegionService.class);
    }

    private MockMvc mockMvcFor(CurrentUser user, VisualGeoProperties properties) {
        return MockMvcBuilders
                .standaloneSetup(new GeoRegionController(referenceRegionService, user, properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private MockMvc mockMvc() {
        return mockMvcFor(currentUser, new VisualGeoProperties(true));
    }

    /** A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} but a caller-supplied scope. */
    private CurrentUser currentUserWithScope(VisibilityScope scope) {
        return new CurrentUser(new PrincipalResolver() {
            @Override
            public UserId userId() {
                return ownerId;
            }

            @Override
            public Ownership ownership() {
                return ownership;
            }

            @Override
            public VisibilityScope scope() {
                return scope;
            }

            @Override
            public MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("GeoRegionController never calls viewer()");
            }
        });
    }

    private static ReferenceRegion buildingRegion() {
        return new ReferenceRegion("kyiv-pozniaky", "kyiv-pozniaky",
                new RegionBounds(50.46, 50.44, 30.53, 30.51), 17, RegionStatus.BUILDING, null);
    }

    @Test
    void listReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, new VisualGeoProperties(false));

        disabledMvc.perform(get("/api/geo/regions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void ingestReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, new VisualGeoProperties(false));

        disabledMvc.perform(post("/api/geo/regions").contentType(MediaType.APPLICATION_JSON).content(REGION_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void deleteReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, new VisualGeoProperties(false));

        disabledMvc.perform(delete("/api/geo/regions/kyiv-pozniaky"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void progressReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(currentUser, new VisualGeoProperties(false));

        disabledMvc.perform(get("/api/geo/regions/kyiv-pozniaky/progress"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void listReturnsEveryKnownRegion() throws Exception {
        when(referenceRegionService.list()).thenReturn(List.of(buildingRegion()));

        mockMvc().perform(get("/api/geo/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regions", hasSize(1)))
                .andExpect(jsonPath("$.regions[0].regionId").value("kyiv-pozniaky"))
                .andExpect(jsonPath("$.regions[0].status").value("BUILDING"))
                .andExpect(jsonPath("$.regions[0].tileCount").doesNotExist());
    }

    @Test
    void ingestReturns403WhenTheCallerMayNotAdminister() throws Exception {
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())),
                new VisualGeoProperties(true));

        pilotMvc.perform(post("/api/geo/regions").contentType(MediaType.APPLICATION_JSON).content(REGION_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void ingestReturns400ForAMalformedBodyBeforeCheckingAuthorization() throws Exception {
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())),
                new VisualGeoProperties(true));
        String badZoom = """
                {"name":"kyiv-pozniaky","north":50.46,"south":50.44,"east":30.53,"west":30.51,"zoom":25}""";

        pilotMvc.perform(post("/api/geo/regions").contentType(MediaType.APPLICATION_JSON).content(badZoom))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void ingestReturns202AndStartsAnAsynchronousBuild() throws Exception {
        when(referenceRegionService.ingest(any(RegionIngestSpec.class))).thenReturn(buildingRegion());

        mockMvc().perform(post("/api/geo/regions").contentType(MediaType.APPLICATION_JSON).content(REGION_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.regionId").value("kyiv-pozniaky"))
                .andExpect(jsonPath("$.status").value("BUILDING"));
    }

    @Test
    void deleteReturns403WhenTheCallerMayNotAdminister() throws Exception {
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())),
                new VisualGeoProperties(true));

        pilotMvc.perform(delete("/api/geo/regions/kyiv-pozniaky")).andExpect(status().isForbidden());

        verifyNoInteractions(referenceRegionService);
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc().perform(delete("/api/geo/regions/kyiv-pozniaky")).andExpect(status().isNoContent());
    }

    @Test
    void progressReturns404WhenNoJobAndNoBuiltRegionExists() throws Exception {
        when(referenceRegionService.progress("kyiv-pozniaky")).thenReturn(Optional.empty());

        mockMvc().perform(get("/api/geo/regions/kyiv-pozniaky/progress")).andExpect(status().isNotFound());
    }

    @Test
    void progressReturnsTheLatestUpdate() throws Exception {
        IngestProgress progress = new IngestProgress("kyiv-pozniaky", "indexing", 4, 10, IngestState.RUNNING, "",
                null);
        when(referenceRegionService.progress("kyiv-pozniaky")).thenReturn(Optional.of(progress));

        mockMvc().perform(get("/api/geo/regions/kyiv-pozniaky/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("indexing"))
                .andExpect(jsonPath("$.done").value(4))
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.state").value("RUNNING"))
                .andExpect(jsonPath("$.stats").doesNotExist());
    }

    @Test
    void listTranslatesAnUnexpectedTransportFailureTo503() throws Exception {
        when(referenceRegionService.list()).thenThrow(new RuntimeException("UNAVAILABLE: cv-service"));

        mockMvc().perform(get("/api/geo/regions"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("SERVICE_UNAVAILABLE"));
    }
}
