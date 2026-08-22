package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.support.StreamViewerLinks;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.perception.application.stream.AssetStreamService;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.perception.domain.model.PipelineConfig;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.domain.port.StreamPublisherPort;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.api.security.PrincipalResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.api.security.CurrentUser;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
 * Split off {@link AssetControllerTest} in docs/plans/active/DOMAIN-SEPARATION-W1.md §15, W1.6e —
 * see {@link AssetStreamController}'s own javadoc. Scenarios and assertions moved verbatim; only
 * the target controller/mocks changed.
 */
class AssetStreamControllerTest {

    private AssetService assetService;
    private AssetStreamService assetStreamService;
    private StreamPublisherPort streamPublisherPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        assetStreamService = mock(AssetStreamService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        // StreamViewerLinks is a plain final class wrapping this port (docs/plans/active/CV-DEMAND-PLAN.md
        // §3.8) -- a real instance backed by the mocked port, matching this codebase's established
        // pattern of not mocking final support classes.
        StreamViewerLinks streamViewerLinks = new StreamViewerLinks(streamPublisherPort);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetStreamController(assetService, assetStreamService, currentUser,
                        streamViewerLinks, PipelineConfig.defaults()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /**
     * A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} but a caller-supplied
     * {@link VisibilityScope}, for the docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2 authority tests
     * below -- same idiom {@code AssetControllerTest} uses. {@link PrincipalResolver#viewer()} is
     * never called by this controller, so it throws rather than fake a map viewer no test here needs.
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
                throw new UnsupportedOperationException("AssetStreamController never calls viewer()");
            }
        });
    }

    private MockMvc mockMvcFor(CurrentUser user) {
        StreamViewerLinks streamViewerLinks = new StreamViewerLinks(streamPublisherPort, streamService);
        return MockMvcBuilders
                .standaloneSetup(new AssetStreamController(assetService, assetStreamService, user, streamViewerLinks,
                        PipelineConfig.defaults()))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Device videoDevice() {
        return new Device(DeviceId.random(), "fpv-cam", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://demo"), Map.of()));
    }

    private Asset asset(Device... devices) {
        Set<DeviceId> ids = new LinkedHashSet<>();
        for (Device device : devices) {
            ids.add(device.id());
        }
        return new Asset(AssetId.random(), "my drone", new CategoryId("drone"), ownership, ids,
                Map.of("weightKg", "1.2"));
    }

    // ---- POST /api/assets/{id}/stream ----

    @Test
    void startStreamWithoutDeviceIdPassesNullDeviceAndReturns201() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetStreamService.startStream(eq(asset.id()), isNull(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));

        verify(assetStreamService).startStream(eq(asset.id()), isNull(), any(), any());
    }

    @Test
    void startStreamWithDeviceIdPassesParsedDeviceIdAndOmitsViewUrlWhenAbsent() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetStreamService.startStream(eq(asset.id()), eq(device.id()), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = "{\"deviceId\":\"" + device.id().value() + "\"}";

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").doesNotExist());

        verify(assetStreamService).startStream(eq(asset.id()), eq(device.id()), any(), any());
    }

    @Test
    void startStreamReturns201WithWhepUrlWhenPublisherHasOne() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetStreamService.startStream(eq(asset.id()), isNull(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:18889/" + streamId.value() + "/whep")));

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"))
                .andExpect(jsonPath("$.whepUrl")
                        .value("http://localhost:18889/" + streamId.value() + "/whep"));
    }

    @Test
    void startStreamOmitsWhepUrlWhenPublisherHasNoWebRtcEndpoint() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetStreamService.startStream(eq(asset.id()), isNull(), any(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));
        when(streamPublisherPort.whepUrl(streamId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.whepUrl").doesNotExist());
    }

    @Test
    void startStreamReturns400WhenDeviceIsAmbiguous() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        when(assetStreamService.startStream(eq(asset.id()), isNull(), any(), any())).thenThrow(new IllegalArgumentException(
                "Asset " + asset.id().value() + " has multiple video-capable devices, specify which one to start"));

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startStreamReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetStreamService.startStream(eq(unknown), isNull(), any(), any()))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(post("/api/assets/{id}/stream", unknown.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void startStreamReturns400ForMalformedAssetUuid() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/stream", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
        verifyNoInteractions(assetStreamService);
    }

    // ---- DELETE /api/assets/{id}/stream ----

    @Test
    void stopStreamReturns204AndDelegatesToService() throws Exception {
        AssetId assetId = AssetId.random();

        mockMvc.perform(delete("/api/assets/{id}/stream", assetId.value()))
                .andExpect(status().isNoContent());

        verify(assetService).stopStream(assetId);
    }

    @Test
    void stopStreamReturns400ForMalformedUuid() throws Exception {
        mockMvc.perform(delete("/api/assets/{id}/stream", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(assetService);
    }

    // ---- docs/plans/active/LIVE-SCOPE-PLAN.md §2, W2: authority --------------------------------
    //
    // Before this wave, #stopStream ran with no scope check at all -- any caller could stop any
    // asset's stream. These tests fail without StreamAccess's sibling here, #requireInScope: a
    // PILOT scoped to no assets (or a different one) must 404 on `assetId`, while a PILOT scoped to
    // `assetId` itself must keep working, per this wave's hard constraint.

    @Test
    void stopStreamReturns404ForAPilotScopedToADifferentAsset() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.details(any(VisibilityScope.class), eq(assetId)))
                .thenThrow(new NoSuchElementException("Unknown asset: " + assetId.value()));
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of())));

        pilotMvc.perform(delete("/api/assets/{id}/stream", assetId.value()))
                .andExpect(status().isNotFound());

        verify(assetService, never()).stopStream(any());
    }

    @Test
    void stopStreamReturns204ForAPilotScopedToTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        MockMvc pilotMvc = mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));

        pilotMvc.perform(delete("/api/assets/{id}/stream", assetId.value()))
                .andExpect(status().isNoContent());

        verify(assetService).stopStream(assetId);
    }
}
