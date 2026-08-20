package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.VisualGeoProperties;
import com.drones.vision.flight.application.TrackCorrectionService;
import com.drones.vision.flight.domain.model.CorrectionSource;
import com.drones.vision.flight.domain.model.CorrectionStatus;
import com.drones.vision.flight.domain.model.TrackCorrection;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.kernel.VisualFixEvidence;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for {@link GeoCorrectionController} (docs/plans/active/VISUAL-GEO-V2-PLAN.md §3.3,
 * H5) — the D9 flag-off {@code 409} on both routes, the per-visible-asset {@code /live} read, and
 * the usage-scoped history read's 404-hides-existence rule.
 */
class GeoCorrectionControllerTest {

    private TrackCorrectionService trackCorrectionService;
    private AssetService assetService;
    private AssetUsageRepositoryPort assetUsageRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        trackCorrectionService = mock(TrackCorrectionService.class);
        assetService = mock(AssetService.class);
        assetUsageRepositoryPort = mock(AssetUsageRepositoryPort.class);
        mockMvc = mockMvcFor(new VisualGeoProperties(true));
    }

    private MockMvc mockMvcFor(VisualGeoProperties properties) {
        return MockMvcBuilders
                .standaloneSetup(new GeoCorrectionController(trackCorrectionService, assetService,
                        assetUsageRepositoryPort, currentUser, properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private AssetSummary assetSummary(AssetId assetId) {
        Asset asset = new Asset(assetId, "drone-1", new CategoryId("drone"), ownership, Set.of(DeviceId.random()),
                Map.of());
        return new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
    }

    private static AssetUsage usage(UsageId usageId, AssetId assetId) {
        return new AssetUsage(usageId, assetId, Instant.parse("2026-08-19T09:00:00Z"), null, null, null, 0L, null,
                UsagePhase.IN_FLIGHT);
    }

    private static TrackCorrection noFix(AssetId assetId, UsageId usageId, Instant frameAt) {
        VisualFixEvidence evidence = new VisualFixEvidence(0, 0, 0, 0.0, 0.0, 0.0, false, false, 0, 0.0, false, 0.0,
                0, 1.0);
        return new TrackCorrection(assetId, usageId, frameAt, frameAt.plusSeconds(1), CorrectionStatus.NO_FIX,
                CorrectionSource.VISUAL_HEAVY, null, null, null, null, null, null, null, false, null, "", "",
                "no_match", evidence);
    }

    @Test
    void liveReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(new VisualGeoProperties(false));

        disabledMvc.perform(get("/api/geo/corrections/live"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(trackCorrectionService, assetService);
    }

    @Test
    void forUsageReturns409WhenDisabled() throws Exception {
        MockMvc disabledMvc = mockMvcFor(new VisualGeoProperties(false));

        disabledMvc.perform(get("/api/geo/corrections").param("usageId", UsageId.random().value().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualGeoProperties.DISABLED_MESSAGE));

        verifyNoInteractions(trackCorrectionService, assetUsageRepositoryPort);
    }

    @Test
    void liveReturnsOneCorrectionPerVisibleAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.assets(any(VisibilityScope.class), eq(false))).thenReturn(List.of(assetSummary(assetId)));
        TrackCorrection correction = noFix(assetId, UsageId.random(), Instant.parse("2026-08-19T10:00:00Z"));
        when(trackCorrectionService.latest(eq(assetId), any(VisibilityScope.class)))
                .thenReturn(Optional.of(correction));

        mockMvc.perform(get("/api/geo/corrections/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrections", hasSize(1)))
                .andExpect(jsonPath("$.corrections[0].assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.corrections[0].status").value("NO_FIX"));
    }

    @Test
    void liveSkipsAssetsWithNoCorrectionYet() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.assets(any(VisibilityScope.class), eq(false))).thenReturn(List.of(assetSummary(assetId)));
        when(trackCorrectionService.latest(eq(assetId), any(VisibilityScope.class))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/geo/corrections/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrections", hasSize(0)));
    }

    @Test
    void forUsageReturns404WhenUsageIsUnknown() throws Exception {
        UsageId usageId = UsageId.random();
        when(assetUsageRepositoryPort.findById(usageId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/geo/corrections").param("usageId", usageId.value().toString()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(trackCorrectionService);
    }

    @Test
    void forUsageReturns404WhenTheOwningAssetIsOutOfScope() throws Exception {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        when(assetUsageRepositoryPort.findById(usageId)).thenReturn(Optional.of(usage(usageId, assetId)));
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("out of scope"));

        mockMvc.perform(get("/api/geo/corrections").param("usageId", usageId.value().toString()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(trackCorrectionService);
    }

    @Test
    void forUsageReturnsTheServicesCorrections() throws Exception {
        AssetId assetId = AssetId.random();
        UsageId usageId = UsageId.random();
        when(assetUsageRepositoryPort.findById(usageId)).thenReturn(Optional.of(usage(usageId, assetId)));
        when(assetService.details(any(VisibilityScope.class), eq(assetId))).thenReturn(mock(AssetDetails.class));
        TrackCorrection correction = noFix(assetId, usageId, Instant.parse("2026-08-19T10:00:00Z"));
        when(trackCorrectionService.forUsage(eq(usageId), eq(GeoCorrectionController.DEFAULT_LIMIT),
                any(VisibilityScope.class))).thenReturn(List.of(correction));

        mockMvc.perform(get("/api/geo/corrections").param("usageId", usageId.value().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.corrections", hasSize(1)))
                .andExpect(jsonPath("$.corrections[0].usageId").value(usageId.value().toString()));
    }

    @Test
    void forUsageRejectsALimitOutsideTheFrozenBounds() throws Exception {
        UsageId usageId = UsageId.random();

        mockMvc.perform(get("/api/geo/corrections").param("usageId", usageId.value().toString())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/geo/corrections").param("usageId", usageId.value().toString())
                        .param("limit", String.valueOf(GeoCorrectionController.MAX_LIMIT + 1)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetUsageRepositoryPort);
    }
}
