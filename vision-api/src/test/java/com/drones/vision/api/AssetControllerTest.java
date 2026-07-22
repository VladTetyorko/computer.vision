package com.drones.vision.api;

import com.drones.vision.application.AssetDetails;
import com.drones.vision.application.AssetService;
import com.drones.vision.application.AssetSpec;
import com.drones.vision.application.AssetStatus;
import com.drones.vision.application.AssetSummary;
import com.drones.vision.application.DeviceRegistration;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.StreamPublisherPort;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetControllerTest {

    private AssetService assetService;
    private StreamPublisherPort streamPublisherPort;
    private TelemetryRepositoryPort telemetryRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        telemetryRepositoryPort = mock(TelemetryRepositoryPort.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetController(assetService, currentUser, streamPublisherPort,
                        telemetryRepositoryPort))
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

    /** Stubs {@link #assetService} so {@code asset} resolves as an existing asset. */
    private void stubExistingAsset(Asset asset, Device... devices) {
        AssetSummary summary = new AssetSummary(asset, "Drone",
                AssetStatus.OFFLINE, null, null);
        AssetDetails details =
                new AssetDetails(summary, List.of(devices), List.of());
        when(assetService.details(asset.id())).thenReturn(details);
    }

    // ---- POST /api/assets ----

    @Test
    void createReturns201WithAssetDetailsAndMapsNestedDeviceRegistration() throws Exception {
        Device device = videoDevice();
        Asset created = asset(device);
        when(assetService.create(any(), any(), any())).thenReturn(created);

        AssetSummary summary = new AssetSummary(created, "Drone",
                AssetStatus.OFFLINE, null, null);
        AssetDetails details =
                new AssetDetails(summary, List.of(device), List.of());
        when(assetService.details(created.id())).thenReturn(details);

        String body = """
                {"displayName":"my drone","category":"drone","attributes":{"weightKg":"1.2"},
                 "devices":[{"name":"fpv-cam","protocol":"sim","uri":"sim://demo","options":{"loop":"true"}}]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.assetId").value(created.id().value().toString()))
                .andExpect(jsonPath("$.displayName").value("my drone"))
                .andExpect(jsonPath("$.category").value("drone"))
                .andExpect(jsonPath("$.categoryName").value("Drone"))
                .andExpect(jsonPath("$.owner").value(ownerId.value().toString()))
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$.lastKnownPosition").doesNotExist())
                .andExpect(jsonPath("$.attributes.weightKg").value("1.2"))
                .andExpect(jsonPath("$.devices", hasSize(1)))
                .andExpect(jsonPath("$.devices[0].id").value(device.id().value().toString()))
                .andExpect(jsonPath("$.devices[0].name").value("fpv-cam"))
                .andExpect(jsonPath("$.recentUsages", hasSize(0)));

        ArgumentCaptor<AssetSpec> captor =
                ArgumentCaptor.forClass(AssetSpec.class);
        verify(assetService).create(captor.capture(), any(), any());
        AssetSpec spec = captor.getValue();
        assertEquals("my drone", spec.displayName());
        assertEquals(new CategoryId("drone"), spec.category());
        assertEquals(Map.of("weightKg", "1.2"), spec.attributes());
        assertEquals(1, spec.devices().size());

        DeviceRegistration registration = spec.devices().get(0);
        assertEquals("fpv-cam", registration.name());
        assertEquals(Set.of(Capability.VIDEO), registration.capabilities());
        assertEquals("sim", registration.stream().protocol());
        assertEquals(URI.create("sim://demo"), registration.stream().uri());
        assertEquals(Map.of("loop", "true"), registration.stream().options());
    }

    @Test
    void createAcceptsExplicitCapabilitiesAndMapsThemThroughToRegistration() throws Exception {
        // A device wired for TELEMETRY (not just the default VIDEO) is what lets
        // UsageTracker record positions/sampleCount for assets built via this API.
        Device device = videoDevice();
        Asset created = asset(device);
        when(assetService.create(any(), any(), any())).thenReturn(created);
        stubExistingAsset(created, device);

        String body = """
                {"displayName":"my drone","category":"drone",
                 "devices":[{"name":"fpv-cam","protocol":"sim","uri":"sim://demo","capabilities":["VIDEO","TELEMETRY"]}]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<AssetSpec> captor =
                ArgumentCaptor.forClass(AssetSpec.class);
        verify(assetService).create(captor.capture(), any(), any());
        DeviceRegistration registration = captor.getValue().devices().get(0);
        assertEquals(Set.of(Capability.VIDEO, Capability.TELEMETRY), registration.capabilities());
    }

    @Test
    void createReturns400ForUnknownDeviceCapability() throws Exception {
        String body = """
                {"displayName":"my drone","category":"drone",
                 "devices":[{"name":"fpv-cam","protocol":"sim","uri":"sim://demo","capabilities":["LASER"]}]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Unknown capability: LASER (valid values: VIDEO, TELEMETRY, PTZ, AUDIO)"));

        verifyNoInteractions(assetService);
    }

    @Test
    void createReturns400WhenServiceThrowsForUnknownCategory() throws Exception {
        when(assetService.create(any(), any(), any())).thenThrow(new IllegalArgumentException("Unknown category: bogus"));

        String body = """
                {"displayName":"my drone","category":"bogus",
                 "devices":[{"name":"cam","protocol":"sim","uri":"sim://demo"}]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unknown category: bogus"));
    }

    @Test
    void createReturns400ForZeroDevices() throws Exception {
        String body = """
                {"displayName":"my drone","category":"drone","devices":[]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
    }

    // ---- GET /api/assets ----

    @Test
    void listReturnsSummariesOmittingAbsentLastUsedAndPosition() throws Exception {
        Asset neverUsed = asset(videoDevice());
        AssetSummary neverUsedSummary = new AssetSummary(neverUsed, "Drone",
                AssetStatus.OFFLINE, null, null);

        Asset used = asset(videoDevice());
        Instant lastUsedAt = Instant.parse("2026-07-20T10:00:00Z");
        GeoPosition position = new GeoPosition(50.45, 30.52, 120.0);
        AssetSummary usedSummary = new AssetSummary(used, "Drone",
                AssetStatus.STREAMING, lastUsedAt, position);

        when(assetService.assets(false)).thenReturn(List.of(neverUsedSummary, usedSummary));

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].assetId").value(neverUsed.id().value().toString()))
                .andExpect(jsonPath("$[0].status").value("OFFLINE"))
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist())
                .andExpect(jsonPath("$[0].lastKnownPosition").doesNotExist())
                .andExpect(jsonPath("$[1].assetId").value(used.id().value().toString()))
                .andExpect(jsonPath("$[1].status").value("STREAMING"))
                .andExpect(jsonPath("$[1].lastUsedAt").value("2026-07-20T10:00:00Z"))
                .andExpect(jsonPath("$[1].lastKnownPosition.latitude").value(50.45))
                .andExpect(jsonPath("$[1].lastKnownPosition.longitude").value(30.52))
                .andExpect(jsonPath("$[1].lastKnownPosition.altitudeMeters").value(120.0));
    }

    @Test
    void listReturns200WithEmptyListWhenNoAssets() throws Exception {
        when(assetService.assets(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- GET /api/assets/{id} ----

    @Test
    void detailsReturns200WithDevicesAndUsageHistory() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);
        AssetSummary summary = new AssetSummary(asset, "Drone",
                AssetStatus.OFFLINE, null, null);

        AssetUsage closedUsage = new AssetUsage(UsageId.random(), asset.id(),
                Instant.parse("2026-07-20T10:00:00Z"), Instant.parse("2026-07-20T10:05:00Z"),
                new GeoPosition(50.45, 30.52, null), new GeoPosition(50.46, 30.53, null), 42);
        AssetUsage openUsage = new AssetUsage(UsageId.random(), asset.id(),
                Instant.parse("2026-07-21T09:00:00Z"), null, null, null, 0);

        AssetDetails details = new AssetDetails(summary,
                List.of(device), List.of(openUsage, closedUsage));
        when(assetService.details(asset.id())).thenReturn(details);

        mockMvc.perform(get("/api/assets/{id}", asset.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(asset.id().value().toString()))
                .andExpect(jsonPath("$.devices", hasSize(1)))
                .andExpect(jsonPath("$.devices[0].id").value(device.id().value().toString()))
                .andExpect(jsonPath("$.recentUsages", hasSize(2)))
                .andExpect(jsonPath("$.recentUsages[0].usageId").value(openUsage.id().value().toString()))
                .andExpect(jsonPath("$.recentUsages[0].endedAt").doesNotExist())
                .andExpect(jsonPath("$.recentUsages[0].startPosition").doesNotExist())
                .andExpect(jsonPath("$.recentUsages[0].sampleCount").value(0))
                .andExpect(jsonPath("$.recentUsages[1].usageId").value(closedUsage.id().value().toString()))
                .andExpect(jsonPath("$.recentUsages[1].endedAt").value("2026-07-20T10:05:00Z"))
                .andExpect(jsonPath("$.recentUsages[1].startPosition.latitude").value(50.45))
                .andExpect(jsonPath("$.recentUsages[1].lastPosition.latitude").value(50.46))
                .andExpect(jsonPath("$.recentUsages[1].sampleCount").value(42));
    }

    @Test
    void detailsReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.details(unknown))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(get("/api/assets/{id}", unknown.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void detailsReturns400ForMalformedUuid() throws Exception {
        mockMvc.perform(get("/api/assets/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
    }

    // ---- POST /api/assets/{id}/stream ----

    @Test
    void startStreamWithoutDeviceIdPassesNullDeviceAndReturns201() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetService.startStream(eq(asset.id()), isNull(), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId))
                .thenReturn(Optional.of(URI.create("http://localhost:8888/" + streamId.value() + "/index.m3u8")));

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl")
                        .value("http://localhost:8888/" + streamId.value() + "/index.m3u8"));

        verify(assetService).startStream(eq(asset.id()), isNull(), any());
    }

    @Test
    void startStreamWithDeviceIdPassesParsedDeviceIdAndOmitsViewUrlWhenAbsent() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetService.startStream(eq(asset.id()), eq(device.id()), any())).thenReturn(streamId);
        when(streamPublisherPort.viewUrl(streamId)).thenReturn(Optional.empty());

        String body = "{\"deviceId\":\"" + device.id().value() + "\"}";

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(streamId.value().toString()))
                .andExpect(jsonPath("$.viewUrl").doesNotExist());

        verify(assetService).startStream(eq(asset.id()), eq(device.id()), any());
    }

    @Test
    void startStreamReturns400WhenDeviceIsAmbiguous() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        when(assetService.startStream(eq(asset.id()), isNull(), any())).thenThrow(new IllegalArgumentException(
                "Asset " + asset.id().value() + " has multiple video-capable devices, specify which one to start"));

        mockMvc.perform(post("/api/assets/{id}/stream", asset.id().value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void startStreamReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.startStream(eq(unknown), isNull(), any()))
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

    // ---- GET /api/usages/{usageId}/telemetry ----

    @Test
    void telemetryReturnsSamplesWithDefaultLimitOf100() throws Exception {
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        Telemetry sample = new Telemetry(deviceId, Instant.parse("2026-07-20T10:00:00Z"), 50.45, 30.52, 120.0, 90.0,
                87.5, Map.of());
        when(telemetryRepositoryPort.findByUsage(usageId, 100)).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].at").value("2026-07-20T10:00:00Z"))
                .andExpect(jsonPath("$[0].latitude").value(50.45))
                .andExpect(jsonPath("$[0].longitude").value(30.52))
                .andExpect(jsonPath("$[0].altitudeMeters").value(120.0))
                .andExpect(jsonPath("$[0].headingDegrees").value(90.0))
                .andExpect(jsonPath("$[0].batteryPercent").value(87.5));

        verify(telemetryRepositoryPort).findByUsage(usageId, 100);
    }

    @Test
    void telemetryUsesExplicitLimitQueryParam() throws Exception {
        UsageId usageId = UsageId.random();
        when(telemetryRepositoryPort.findByUsage(eq(usageId), anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()).param("limit", "5"))
                .andExpect(status().isOk());

        verify(telemetryRepositoryPort).findByUsage(usageId, 5);
    }

    @Test
    void telemetryReturnsEmptyListForUnknownUsagePerPortContract() throws Exception {
        // TelemetryRepositoryPort#findByUsage documents no "unknown usage"
        // failure mode; an unrecognized id behaves exactly like a usage with
        // no recorded samples (empty list), not a 404.
        UsageId unknown = UsageId.random();
        when(telemetryRepositoryPort.findByUsage(unknown, 100)).thenReturn(List.of());

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", unknown.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void telemetryReturns400ForMalformedUsageUuid() throws Exception {
        mockMvc.perform(get("/api/usages/{usageId}/telemetry", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(telemetryRepositoryPort);
    }
}
