package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.warehouse.application.asset.AssetDetails;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetStatus;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.identity.application.scope.VisibilityScope;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetImage;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetImageControllerTest {

    private AssetService assetService;
    private AssetImageRepositoryPort assetImageRepositoryPort;
    private MockMvc mockMvc;

    private final AssetId assetId = AssetId.random();
    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        assetImageRepositoryPort = mock(AssetImageRepositoryPort.class);
        // In scope by default: a CurrentUser built from a plain Ownership resolves to an unbounded
        // scope, and AssetService#details is stubbed to succeed for assetId unless a test overrides
        // it below to simulate an out-of-scope/unknown asset.
        when(assetService.details(any(VisibilityScope.class), eq(assetId))).thenReturn(details(assetId));
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetImageController(assetService, currentUser, assetImageRepositoryPort))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static AssetDetails details(AssetId id) {
        Device device = new Device(DeviceId.random(), "fpv-cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://demo"), Map.of()));
        Asset asset = new Asset(id, "my drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(device.id()), Map.of());
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        return new AssetDetails(summary, List.of(device), List.of());
    }

    // ---- PUT /api/assets/{id}/image ----

    @Test
    void putStoresTheImageAndReturns204() throws Exception {
        byte[] body = {1, 2, 3, 4, 5};

        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("image/jpeg").content(body))
                .andExpect(status().isNoContent());

        ArgumentCaptor<AssetImage> captor = ArgumentCaptor.forClass(AssetImage.class);
        verify(assetImageRepositoryPort).save(eq(assetId), captor.capture());
        assertArrayEquals(body, captor.getValue().data());
        assertEquals("image/jpeg", captor.getValue().contentType());
    }

    @Test
    void putAcceptsPngContentType() throws Exception {
        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("image/png").content(new byte[]{9, 9}))
                .andExpect(status().isNoContent());

        ArgumentCaptor<AssetImage> captor = ArgumentCaptor.forClass(AssetImage.class);
        verify(assetImageRepositoryPort).save(eq(assetId), captor.capture());
        assertEquals("image/png", captor.getValue().contentType());
    }

    @Test
    void putReturns400ForAnUnsupportedContentType() throws Exception {
        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("application/octet-stream").content(new byte[]{1}))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetImageRepositoryPort);
    }

    @Test
    void putReturns400ForAMissingContentType() throws Exception {
        mockMvc.perform(put("/api/assets/{id}/image", assetId.value()).content(new byte[]{1}))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetImageRepositoryPort);
    }

    @Test
    void putReturns413ForAnOversizedBody() throws Exception {
        byte[] tooBig = new byte[AssetImageController.MAX_IMAGE_BYTES + 1];

        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("image/jpeg").content(tooBig))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));

        verifyNoInteractions(assetImageRepositoryPort);
    }

    @Test
    void putReturns400ForAnEmptyBody() throws Exception {
        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("image/jpeg").content(new byte[0]))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetImageRepositoryPort);
    }

    @Test
    void putReturns404ForAnOutOfScopeAsset() throws Exception {
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Asset outside scope: " + assetId.value()));

        mockMvc.perform(put("/api/assets/{id}/image", assetId.value())
                        .contentType("image/jpeg").content(new byte[]{1, 2, 3}))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verifyNoInteractions(assetImageRepositoryPort);
    }

    @Test
    void putReturns404ForAnUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(unknown)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(put("/api/assets/{id}/image", unknown.value())
                        .contentType("image/jpeg").content(new byte[]{1, 2, 3}))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verifyNoInteractions(assetImageRepositoryPort);
    }

    // ---- GET /api/assets/{id}/image ----

    @Test
    void getReturnsTheStoredImageWithItsContentType() throws Exception {
        byte[] data = {5, 4, 3, 2, 1};
        when(assetImageRepositoryPort.findByAssetId(assetId))
                .thenReturn(Optional.of(new AssetImage(data, "image/png")));

        mockMvc.perform(get("/api/assets/{id}/image", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(result -> assertArrayEquals(data, result.getResponse().getContentAsByteArray()));
    }

    @Test
    void getReturns404WhenNoImageIsStored() throws Exception {
        when(assetImageRepositoryPort.findByAssetId(assetId)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/assets/{id}/image", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void getReturns400ForAMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/assets/{id}/image", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturns404ForAnOutOfScopeAssetWithoutTouchingTheImagePort() throws Exception {
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Asset outside scope: " + assetId.value()));

        mockMvc.perform(get("/api/assets/{id}/image", assetId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verifyNoInteractions(assetImageRepositoryPort);
    }

    // ---- DELETE /api/assets/{id}/image ----

    @Test
    void deleteRemovesTheImageAndReturns204() throws Exception {
        mockMvc.perform(delete("/api/assets/{id}/image", assetId.value()))
                .andExpect(status().isNoContent());

        verify(assetImageRepositoryPort, times(1)).deleteByAssetId(assetId);
    }

    @Test
    void deleteReturns400ForAMalformedUuid() throws Exception {
        mockMvc.perform(delete("/api/assets/{id}/image", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturns404ForAnUnknownAssetInsteadOfASilentNoOp() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(unknown)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(delete("/api/assets/{id}/image", unknown.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verifyNoInteractions(assetImageRepositoryPort);
    }
}
