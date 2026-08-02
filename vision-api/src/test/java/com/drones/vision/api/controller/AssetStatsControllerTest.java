package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.application.asset.AssetService;
import com.drones.vision.application.asset.AssetStats;
import com.drones.vision.application.asset.AssetStatsService;
import com.drones.vision.domain.model.AssetId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetStatsControllerTest {

    private AssetService assetService;
    private AssetStatsService assetStatsService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        assetStatsService = mock(AssetStatsService.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetStatsController(assetService, assetStatsService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void statsReturns200WithTheFullShapeWhenEveryOptionalIsPresent() throws Exception {
        AssetId assetId = AssetId.random();
        Instant first = Instant.parse("2026-01-01T00:00:00Z");
        Instant last = Instant.parse("2026-01-02T00:02:00Z");
        AssetStats stats = new AssetStats(180L, 2, first, last, 90L, 68, true);
        when(assetStatsService.statsFor(assetId)).thenReturn(stats);

        mockMvc.perform(get("/api/assets/{id}/stats", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFlightSeconds").value(180))
                .andExpect(jsonPath("$.flightCount").value(2))
                .andExpect(jsonPath("$.firstFlownAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.lastFlownAt").value("2026-01-02T00:02:00Z"))
                .andExpect(jsonPath("$.avgFlightSeconds").value(90))
                .andExpect(jsonPath("$.lastKnownBatteryPercent").value(68))
                .andExpect(jsonPath("$.flightInProgress").value(true));

        verify(assetService).details(assetId);
        verify(assetStatsService).statsFor(assetId);
    }

    @Test
    void statsOmitsEveryNullableFieldWhenTheAssetHasNoFlightsOrTelemetry() throws Exception {
        AssetId assetId = AssetId.random();
        AssetStats stats = new AssetStats(0L, 0, null, null, null, null, false);
        when(assetStatsService.statsFor(assetId)).thenReturn(stats);

        mockMvc.perform(get("/api/assets/{id}/stats", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalFlightSeconds").value(0))
                .andExpect(jsonPath("$.flightCount").value(0))
                .andExpect(jsonPath("$.firstFlownAt").doesNotExist())
                .andExpect(jsonPath("$.lastFlownAt").doesNotExist())
                .andExpect(jsonPath("$.avgFlightSeconds").doesNotExist())
                .andExpect(jsonPath("$.lastKnownBatteryPercent").doesNotExist())
                .andExpect(jsonPath("$.flightInProgress").value(false));
    }

    @Test
    void statsReturns404ForAnUnknownAssetAndNeverCallsAssetStatsService() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(eq(assetId))).thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(get("/api/assets/{id}/stats", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verifyNoInteractions(assetStatsService);
    }

    @Test
    void statsReturns400ForABadUuidAndNeverTouchesEitherCollaborator() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/stats", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService, assetStatsService);
    }
}
