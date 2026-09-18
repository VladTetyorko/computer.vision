package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.flight.application.link.LinkStateService;
import com.drones.vision.flight.domain.model.CarrierKind;
import com.drones.vision.flight.domain.model.LinkGroupView;
import com.drones.vision.flight.domain.model.LinkId;
import com.drones.vision.flight.domain.model.LinkView;
import com.drones.vision.flight.domain.model.SerialRole;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.platform.VisibilityScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LINK-PAIRING-PLAN.md §3.4/§4 row L3 — proves {@link AssetLinksController}'s three handlers map
 * onto {@link LinkStateService} correctly and that every one of them 404s an out-of-scope/unknown
 * asset before touching it, the same "read-scope guards the write" posture {@code
 * AssetStreamControllerTest} already pins for {@link AssetLinksController#requireInScope}'s sibling
 * in that controller.
 */
class AssetLinksControllerTest {

    private LinkStateService linkStateService;
    private AssetService assetService;
    private MockMvc mockMvc;

    /** Unbounded (auth-off-equivalent) by default, matching {@code GeofenceControllerTest}. */
    private final UserId userId = UserId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(userId, GroupId.random()));

    @BeforeEach
    void setUp() {
        linkStateService = mock(LinkStateService.class);
        assetService = mock(AssetService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AssetLinksController(linkStateService, assetService, currentUser))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static LinkGroupView group(AssetId assetId) {
        LinkId linkId = new LinkId("udp:127.0.0.1:14550");
        LinkView link = new LinkView(linkId, CarrierKind.UDP, SerialRole.NONE, "Primary UDP", true, true,
                Duration.ofSeconds(1), null, DeviceId.random());
        return new LinkGroupView(assetId, List.of(link), linkId, false, null);
    }

    @Test
    void linksReturns200WithTheMappedGroup() throws Exception {
        AssetId assetId = AssetId.random();
        when(linkStateService.linksFor(assetId)).thenReturn(group(assetId));

        mockMvc.perform(get("/api/assets/{id}/links", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.links", hasSize(1)))
                .andExpect(jsonPath("$.links[0].carrier").value("UDP"))
                .andExpect(jsonPath("$.pinned").value(false));

        verify(assetService).details(any(VisibilityScope.class), eq(assetId));
    }

    @Test
    void linksReturns404ForAnOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(get("/api/assets/{id}/links", assetId.value()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(linkStateService);
    }

    @Test
    void linksReturns400ForAMalformedAssetId() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/links", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetService, linkStateService);
    }

    @Test
    void pinDelegatesToLinkStateServiceWithTheActingUserAndReturnsTheResultingSnapshot() throws Exception {
        AssetId assetId = AssetId.random();
        LinkId linkId = new LinkId("udp:127.0.0.1:14550");
        when(linkStateService.pin(assetId, linkId, userId)).thenReturn(group(assetId));

        mockMvc.perform(put("/api/assets/{id}/links/{linkId}/pin", assetId.value(), linkId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeLinkId").value(linkId.value()));

        verify(linkStateService).pin(assetId, linkId, userId);
    }

    @Test
    void pinReturns404ForAnOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(put("/api/assets/{id}/links/{linkId}/pin", assetId.value(), "udp:127.0.0.1:14550"))
                .andExpect(status().isNotFound());

        verify(linkStateService, never()).pin(any(), any(), any());
    }

    @Test
    void releaseDelegatesToLinkStateServiceAndReturnsTheResultingSnapshot() throws Exception {
        AssetId assetId = AssetId.random();
        when(linkStateService.release(assetId, userId)).thenReturn(group(assetId));

        mockMvc.perform(delete("/api/assets/{id}/links/pin", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()));

        verify(linkStateService).release(assetId, userId);
    }

    @Test
    void releaseReturns404ForAnOutOfScopeAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));

        mockMvc.perform(delete("/api/assets/{id}/links/pin", assetId.value()))
                .andExpect(status().isNotFound());

        verify(linkStateService, never()).release(any(), any());
    }
}
