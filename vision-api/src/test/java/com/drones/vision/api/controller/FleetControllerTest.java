package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.asset.AssetAttention;
import com.drones.vision.application.category.CategoryCounts;
import com.drones.vision.application.fleet.FleetSummary;
import com.drones.vision.application.fleet.FleetSummaryService;
import com.drones.vision.application.scope.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shape/mapping/wiring tests only (docs/plans/done/MVP3-PLAN.md C-a) — the counts math, per-category
 * accumulation, and battery/staleness derivation are proven against real inputs in {@code
 * DefaultFleetSummaryServiceTest} (vision-application), the same split this codebase already makes
 * between e.g. {@code UsageTimelineControllerTest} (pass-through/mapping) and {@code
 * DefaultReplayServiceTest} (the actual windowing/downsampling math).
 */
class FleetControllerTest {

    private FleetSummaryService fleetSummaryService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        fleetSummaryService = mock(FleetSummaryService.class);
        CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));
        mockMvc = MockMvcBuilders.standaloneSetup(new FleetController(fleetSummaryService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void summaryMapsCategoriesAndAssetsShape() throws Exception {
        CategoryCounts drone = new CategoryCounts(new CategoryId("drone"), "Drone", 2, 2, 0, 0, 1);
        AssetId assetId = AssetId.random();
        StreamId streamId = StreamId.random();
        AssetAttention row = new AssetAttention(assetId, "Drone A", new CategoryId("drone"), "Drone",
                LifecycleState.ACTIVE, true, streamId, 87.5, 1500L, 2, "RTL", true, true);
        when(fleetSummaryService.summary(any(VisibilityScope.class), eq(false))).thenReturn(new FleetSummary(List.of(drone), List.of(row), 2));

        mockMvc.perform(get("/api/fleet/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssets").value(2))
                .andExpect(jsonPath("$.categories", hasSize(1)))
                .andExpect(jsonPath("$.categories[0].categoryId").value("drone"))
                .andExpect(jsonPath("$.categories[0].categoryName").value("Drone"))
                .andExpect(jsonPath("$.categories[0].total").value(2))
                .andExpect(jsonPath("$.categories[0].active").value(2))
                .andExpect(jsonPath("$.categories[0].deactivated").value(0))
                .andExpect(jsonPath("$.categories[0].deleted").value(0))
                .andExpect(jsonPath("$.categories[0].streaming").value(1))
                .andExpect(jsonPath("$.assets", hasSize(1)))
                .andExpect(jsonPath("$.assets[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.assets[0].displayName").value("Drone A"))
                .andExpect(jsonPath("$.assets[0].categoryId").value("drone"))
                .andExpect(jsonPath("$.assets[0].categoryName").value("Drone"))
                .andExpect(jsonPath("$.assets[0].lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.assets[0].streaming").value(true))
                .andExpect(jsonPath("$.assets[0].streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.assets[0].batteryPercent").value(87.5))
                .andExpect(jsonPath("$.assets[0].telemetryAgeMs").value(1500))
                .andExpect(jsonPath("$.assets[0].openEventCount").value(2))
                .andExpect(jsonPath("$.assets[0].flightMode").value("RTL"))
                .andExpect(jsonPath("$.assets[0].armed").value(true))
                .andExpect(jsonPath("$.assets[0].failsafe").value(true));
    }

    @Test
    void summaryOmitsAbsentOptionalFieldsOnAnAssetRow() throws Exception {
        AssetAttention row = new AssetAttention(AssetId.random(), "Drone B", new CategoryId("drone"), "Drone",
                LifecycleState.ACTIVE, false, null, null, null, 0, null, null, null);
        when(fleetSummaryService.summary(any(VisibilityScope.class), eq(false))).thenReturn(new FleetSummary(List.of(), List.of(row), 1));

        mockMvc.perform(get("/api/fleet/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].streaming").value(false))
                .andExpect(jsonPath("$.assets[0].streamId").doesNotExist())
                .andExpect(jsonPath("$.assets[0].batteryPercent").doesNotExist())
                .andExpect(jsonPath("$.assets[0].telemetryAgeMs").doesNotExist())
                .andExpect(jsonPath("$.assets[0].flightMode").doesNotExist())
                .andExpect(jsonPath("$.assets[0].armed").doesNotExist())
                .andExpect(jsonPath("$.assets[0].failsafe").doesNotExist());
    }

    @Test
    void summaryDefaultsIncludeArchivedToFalse() throws Exception {
        when(fleetSummaryService.summary(any(VisibilityScope.class), anyBoolean())).thenReturn(new FleetSummary(List.of(), List.of(), 0));

        mockMvc.perform(get("/api/fleet/summary")).andExpect(status().isOk());

        verify(fleetSummaryService).summary(any(VisibilityScope.class), eq(false));
    }

    @Test
    void summaryPassesIncludeArchivedTrueThrough() throws Exception {
        when(fleetSummaryService.summary(any(VisibilityScope.class), anyBoolean())).thenReturn(new FleetSummary(List.of(), List.of(), 0));

        mockMvc.perform(get("/api/fleet/summary").param("includeArchived", "true")).andExpect(status().isOk());

        verify(fleetSummaryService).summary(any(VisibilityScope.class), eq(true));
    }

    @Test
    void summaryReturns400ForAMalformedIncludeArchivedValue() throws Exception {
        mockMvc.perform(get("/api/fleet/summary").param("includeArchived", "not-a-boolean"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void summaryHandlesAnEmptyFleet() throws Exception {
        when(fleetSummaryService.summary(any(VisibilityScope.class), eq(false))).thenReturn(new FleetSummary(List.of(), List.of(), 0));

        mockMvc.perform(get("/api/fleet/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.assets", hasSize(0)))
                .andExpect(jsonPath("$.totalAssets").value(0));
    }
}
