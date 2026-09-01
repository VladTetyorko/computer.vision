package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.pipeline.UsageTracker;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.PrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md §3 D2, wave R2 — {@link AssetSessionController}
 * is the driving adapter for {@link UsageTracker#engage}/{@code #disengage}; same MockMvc/scope
 * idiom {@link AssetStreamControllerTest} establishes, with {@link UsageTracker} mocked directly
 * (a well-established precedent — see {@code DefaultStreamServiceTest}/{@code VisualGeoRunnerTest}).
 */
class AssetSessionControllerTest {

    private AssetService assetService;
    private UsageTracker usageTracker;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        usageTracker = mock(UsageTracker.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetSessionController(assetService, usageTracker, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * Same idiom {@link AssetStreamControllerTest#currentUserWithScope} establishes.
     */
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
                throw new UnsupportedOperationException("AssetSessionController never calls viewer()");
            }
        });
    }

    private MockMvc mockMvcFor(CurrentUser user) {
        return MockMvcBuilders
                .standaloneSetup(new AssetSessionController(assetService, usageTracker, user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static AssetUsage usage(AssetId assetId, UsageOrigin origin) {
        return new AssetUsage(UsageId.random(), assetId, Instant.parse("2026-08-26T09:00:00Z"), null, null, null, 0L,
                null, UsagePhase.PREFLIGHT, origin, null);
    }

    // ---- POST /api/assets/{id}/session ----

    @Test
    void engageReturns200WithTheOpenedUsage() throws Exception {
        AssetId assetId = AssetId.random();
        AssetUsage opened = usage(assetId, UsageOrigin.OPERATOR).withPilot(ownerId);
        when(usageTracker.engage(assetId, ownerId)).thenReturn(opened);

        mockMvc.perform(post("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usageId").value(opened.id().value().toString()))
                .andExpect(jsonPath("$.origin").value("OPERATOR"))
                .andExpect(jsonPath("$.pilotId").value(ownerId.value().toString()));

        verify(usageTracker).engage(assetId, ownerId);
    }

    /**
     * docs/plans/active/ASSET-FLOWS-PLAN.md §2, D1p: {@link AssetSessionController#engage} must
     * pass {@link CurrentUser#userId()} through to {@link UsageTracker#engage}, not some other
     * value — this pins the exact argument, distinct from {@link #engageReturns200WithTheOpenedUsage}
     * only asserting a response shape.
     */
    @Test
    void engagePassesTheCurrentUsersIdThroughToUsageTrackerEngage() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.engage(eq(assetId), eq(ownerId))).thenReturn(usage(assetId, UsageOrigin.OPERATOR));

        mockMvc.perform(post("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isOk());

        verify(usageTracker).engage(assetId, ownerId);
    }

    @Test
    void engageReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(unknown)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(post("/api/assets/{id}/session", unknown.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verify(usageTracker, never()).engage(any(), any());
    }

    @Test
    void engageReturns409ForADeactivatedAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.engage(assetId, ownerId))
                .thenThrow(new IllegalStateException("Asset is not in service: my drone"));

        mockMvc.perform(post("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void engageReturns400ForMalformedAssetUuid() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/session", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
        verifyNoInteractions(usageTracker);
    }

    @Test
    void engageReturns404ForAPilotScopedToADifferentAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        pilotMvc.perform(post("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isNotFound());

        verify(usageTracker, never()).engage(any(), any());
    }

    @Test
    void engageReturns200ForAPilotScopedToTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.engage(assetId, ownerId)).thenReturn(usage(assetId, UsageOrigin.OPERATOR));
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));

        pilotMvc.perform(post("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isOk());

        verify(usageTracker).engage(assetId, ownerId);
    }

    // ---- DELETE /api/assets/{id}/session ----

    @Test
    void disengageReturns204AndDelegatesToTheTracker() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.disengage(assetId)).thenReturn(Optional.of(usage(assetId, UsageOrigin.OPERATOR)));

        mockMvc.perform(delete("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isNoContent());

        verify(usageTracker).disengage(assetId);
    }

    @Test
    void disengageReturns204WhenNothingWasEngaged() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.disengage(assetId)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isNoContent());

        verify(usageTracker).disengage(assetId);
    }

    @Test
    void disengageReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(unknown)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(delete("/api/assets/{id}/session", unknown.value()))
                .andExpect(status().isNotFound());

        verify(usageTracker, never()).disengage(any());
    }

    @Test
    void disengageReturns400ForMalformedUuid() throws Exception {
        mockMvc.perform(delete("/api/assets/{id}/session", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetService);
        verifyNoInteractions(usageTracker);
    }

    @Test
    void disengageReturns404ForAPilotScopedToADifferentAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        pilotMvc.perform(delete("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isNotFound());

        verify(usageTracker, never()).disengage(any());
    }

    @Test
    void disengageReturns204ForAPilotScopedToTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(usageTracker.disengage(assetId)).thenReturn(Optional.empty());
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));

        pilotMvc.perform(delete("/api/assets/{id}/session", assetId.value()))
                .andExpect(status().isNoContent());

        verify(usageTracker).disengage(assetId);
    }
}
