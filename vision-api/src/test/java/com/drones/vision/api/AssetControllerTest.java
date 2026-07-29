package com.drones.vision.api;

import com.drones.vision.application.AssetDeletion;
import com.drones.vision.application.AssetDetails;
import com.drones.vision.application.AssetEdit;
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
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.FlightState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.StreamId;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetControllerTest {

    private AssetService assetService;
    private StreamPublisherPort streamPublisherPort;
    private TelemetryRepositoryPort telemetryRepositoryPort;
    private AssetImageRepositoryPort assetImageRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);

    @BeforeEach
    void setUp() {
        assetService = mock(AssetService.class);
        streamPublisherPort = mock(StreamPublisherPort.class);
        telemetryRepositoryPort = mock(TelemetryRepositoryPort.class);
        assetImageRepositoryPort = mock(AssetImageRepositoryPort.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AssetController(assetService, currentUser, streamPublisherPort,
                        telemetryRepositoryPort, assetImageRepositoryPort))
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

    // ---- POST /api/assets with deviceIds ("promote to asset") -----------------

    @Test
    void createAcceptsExistingDeviceIdsWithNoNewDevices() throws Exception {
        Device existing = videoDevice();
        Asset created = asset(existing);
        when(assetService.create(any(), any(), any())).thenReturn(created);
        stubExistingAsset(created, existing);

        String body = """
                {"displayName":"my drone","category":"drone","deviceIds":["%s"]}
                """.formatted(existing.id().value());

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<AssetSpec> captor = ArgumentCaptor.forClass(AssetSpec.class);
        verify(assetService).create(captor.capture(), any(), any());
        AssetSpec spec = captor.getValue();
        assertEquals(0, spec.devices().size());
        assertEquals(List.of(existing.id()), spec.existingDeviceIds());
    }

    @Test
    void createCombinesNewDevicesAndExistingDeviceIdsInOneSpec() throws Exception {
        Device existing = videoDevice();
        Asset created = asset(existing);
        when(assetService.create(any(), any(), any())).thenReturn(created);
        stubExistingAsset(created, existing);

        String body = """
                {"displayName":"my drone","category":"drone",
                 "devices":[{"name":"fpv-cam","protocol":"sim","uri":"sim://demo"}],
                 "deviceIds":["%s"]}
                """.formatted(existing.id().value());

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<AssetSpec> captor = ArgumentCaptor.forClass(AssetSpec.class);
        verify(assetService).create(captor.capture(), any(), any());
        AssetSpec spec = captor.getValue();
        assertEquals(1, spec.devices().size());
        assertEquals(List.of(existing.id()), spec.existingDeviceIds());
    }

    @Test
    void createReturns400ForAMalformedDeviceId() throws Exception {
        String body = """
                {"displayName":"my drone","category":"drone","deviceIds":["not-a-uuid"]}
                """;

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
    }

    @Test
    void createReturns409WhenAnExistingDeviceIdAlreadyBelongsToAnotherAsset() throws Exception {
        DeviceId ownedElsewhere = DeviceId.random();
        when(assetService.create(any(), any(), any()))
                .thenThrow(new IllegalStateException("Device cam-2 already belongs to asset other-drone"));

        String body = """
                {"displayName":"my drone","category":"drone","deviceIds":["%s"]}
                """.formatted(ownedElsewhere.value());

        mockMvc.perform(post("/api/assets").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
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
    void listIncludesHasImagePerAssetFromTheImageRepository() throws Exception {
        Asset withImage = asset(videoDevice());
        AssetSummary withImageSummary = new AssetSummary(withImage, "Drone", AssetStatus.OFFLINE, null, null);
        Asset withoutImage = asset(videoDevice());
        AssetSummary withoutImageSummary = new AssetSummary(withoutImage, "Drone", AssetStatus.OFFLINE, null, null);

        when(assetService.assets(false)).thenReturn(List.of(withImageSummary, withoutImageSummary));
        when(assetImageRepositoryPort.existsByAssetId(withImage.id())).thenReturn(true);
        when(assetImageRepositoryPort.existsByAssetId(withoutImage.id())).thenReturn(false);

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hasImage").value(true))
                .andExpect(jsonPath("$[1].hasImage").value(false));
    }

    @Test
    void listReturns200WithEmptyListWhenNoAssets() throws Exception {
        when(assetService.assets(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/assets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void listExposesLifecycleAlongsideStatusAndPassesIncludeDeletedThrough() throws Exception {
        Asset deleted = asset(videoDevice()).withState(LifecycleState.DELETED);
        AssetSummary summary = new AssetSummary(deleted, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.assets(true)).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/assets").param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lifecycle").value("DELETED"));

        verify(assetService).assets(true);
    }

    // ---- PATCH /api/assets/{id} ----

    @Test
    void updateAppliesTheEditAndReturns200WithAssetDetails() throws Exception {
        Device device = videoDevice();
        Asset stored = asset(device);
        stubExistingAsset(stored, device);

        String body = """
                {"displayName":"renamed drone"}
                """;

        mockMvc.perform(patch("/api/assets/{id}", stored.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(stored.id().value().toString()));

        ArgumentCaptor<AssetEdit> captor = ArgumentCaptor.forClass(AssetEdit.class);
        verify(assetService).update(eq(stored.id()), captor.capture(), any());
        assertEquals("renamed drone", captor.getValue().displayName());
    }

    @Test
    void updateAppliesAnAttributesEditIncludingRegistrationNumber() throws Exception {
        // docs/UX-REWORK-PLAN.md §U-d item 3: confirms attributes patch end-to-end through the
        // HTTP layer — the frontend sends registrationNumber as a plain attributes key, no
        // special-cased field needed anywhere in this path.
        Device device = videoDevice();
        Asset stored = asset(device);
        stubExistingAsset(stored, device);

        String body = """
                {"attributes":{"registrationNumber":"N12345"}}
                """;

        mockMvc.perform(patch("/api/assets/{id}", stored.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<AssetEdit> captor = ArgumentCaptor.forClass(AssetEdit.class);
        verify(assetService).update(eq(stored.id()), captor.capture(), any());
        assertEquals(Map.of("registrationNumber", "N12345"), captor.getValue().attributes());
    }

    @Test
    void updateReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        doThrow(new NoSuchElementException("Unknown asset: " + unknown.value()))
                .when(assetService).update(eq(unknown), any(), any());

        mockMvc.perform(patch("/api/assets/{id}", unknown.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void updateReturns400ForAnUnknownCategory() throws Exception {
        AssetId assetId = AssetId.random();
        doThrow(new IllegalArgumentException("Unknown category: bogus"))
                .when(assetService).update(eq(assetId), any(), any());

        mockMvc.perform(patch("/api/assets/{id}", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"bogus\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- POST /api/assets/{id}/state ----

    @Test
    void setStateMovesToDeactivatedAndReturns200() throws Exception {
        Device device = videoDevice();
        Asset stored = asset(device).withState(LifecycleState.DEACTIVATED);
        stubExistingAsset(stored, device);

        mockMvc.perform(post("/api/assets/{id}/state", stored.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(stored.id().value().toString()));

        verify(assetService).setState(stored.id(), LifecycleState.DEACTIVATED, ownerId);
    }

    @Test
    void setStateReturns400ForAnUnrecognizedStateValueListingValidOnes() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/state", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unknown state: DELETED (valid values: ACTIVE, DEACTIVATED)"));

        verifyNoInteractions(assetService);
    }

    @Test
    void setStateDeactivatedOnADeletedAssetRestoresItPerTheContract() throws Exception {
        Device device = videoDevice();
        Asset stored = asset(device).withState(LifecycleState.DEACTIVATED);
        stubExistingAsset(stored, device);

        mockMvc.perform(post("/api/assets/{id}/state", stored.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk());

        verify(assetService).setState(stored.id(), LifecycleState.DEACTIVATED, ownerId);
    }

    @Test
    void setStateActiveOnADeletedAssetReturns409() throws Exception {
        AssetId assetId = AssetId.random();
        doThrow(new IllegalStateException("Asset my drone is deleted; restore it before putting it back into service"))
                .when(assetService).setState(eq(assetId), eq(LifecycleState.ACTIVE), any());

        mockMvc.perform(post("/api/assets/{id}/state", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void setStateReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        doThrow(new NoSuchElementException("Unknown asset: " + unknown.value()))
                .when(assetService).setState(eq(unknown), any(), any());

        mockMvc.perform(post("/api/assets/{id}/state", unknown.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- DELETE /api/assets/{id} ----

    @Test
    void deleteReturns200WithTheDeletionSummary() throws Exception {
        AssetId assetId = AssetId.random();
        when(assetService.delete(eq(assetId), any()))
                .thenReturn(new AssetDeletion(assetId, "my drone", 2, 5, 1));

        mockMvc.perform(delete("/api/assets/{id}", assetId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(assetId.value().toString()))
                .andExpect(jsonPath("$.displayName").value("my drone"))
                .andExpect(jsonPath("$.devicesDeleted").value(2))
                .andExpect(jsonPath("$.usagesRetained").value(5))
                .andExpect(jsonPath("$.streamsStopped").value(1));
    }

    @Test
    void deleteReturns404ForUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        when(assetService.delete(eq(unknown), any()))
                .thenThrow(new NoSuchElementException("Unknown asset: " + unknown.value()));

        mockMvc.perform(delete("/api/assets/{id}", unknown.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- POST /api/assets/{id}/devices ----

    @Test
    void assignDeviceReturns200WithAssetDetails() throws Exception {
        Device first = videoDevice();
        Device second = videoDevice();
        Asset stored = asset(first, second);
        stubExistingAsset(stored, first, second);
        DeviceId newDevice = DeviceId.random();

        String body = "{\"deviceId\":\"" + newDevice.value() + "\"}";

        mockMvc.perform(post("/api/assets/{id}/devices", stored.id().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(stored.id().value().toString()));

        verify(assetService).assignDevice(stored.id(), newDevice, ownerId);
    }

    @Test
    void assignDeviceReturns409WhenTheDeviceAlreadyBelongsToAnotherAsset() throws Exception {
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        doThrow(new IllegalStateException("Device cam-1 already belongs to asset someone else's drone"))
                .when(assetService).assignDevice(eq(assetId), eq(deviceId), any());

        String body = "{\"deviceId\":\"" + deviceId.value() + "\"}";

        mockMvc.perform(post("/api/assets/{id}/devices", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void assignDeviceReturns404ForAnUnknownDevice() throws Exception {
        AssetId assetId = AssetId.random();
        DeviceId unknownDevice = DeviceId.random();
        doThrow(new NoSuchElementException("Unknown device: " + unknownDevice.value()))
                .when(assetService).assignDevice(eq(assetId), eq(unknownDevice), any());

        String body = "{\"deviceId\":\"" + unknownDevice.value() + "\"}";

        mockMvc.perform(post("/api/assets/{id}/devices", assetId.value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void assignDeviceReturns400ForABlankDeviceId() throws Exception {
        mockMvc.perform(post("/api/assets/{id}/devices", AssetId.random().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(assetService);
    }

    // ---- DELETE /api/assets/{id}/devices/{deviceId} ----

    @Test
    void unassignDeviceReturns200WithAssetDetails() throws Exception {
        Device remaining = videoDevice();
        Asset stored = asset(remaining);
        stubExistingAsset(stored, remaining);
        DeviceId toRemove = DeviceId.random();

        mockMvc.perform(delete("/api/assets/{id}/devices/{deviceId}", stored.id().value(), toRemove.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(stored.id().value().toString()));

        verify(assetService).unassignDevice(stored.id(), toRemove, ownerId);
    }

    @Test
    void unassignDeviceReturns409WhenRemovingTheLastDevice() throws Exception {
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        doThrow(new IllegalStateException("Asset my drone must keep at least one device; unassign refused"))
                .when(assetService).unassignDevice(eq(assetId), eq(deviceId), any());

        mockMvc.perform(delete("/api/assets/{id}/devices/{deviceId}", assetId.value(), deviceId.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void unassignDeviceReturns400WhenTheDeviceDoesNotBelongToTheAsset() throws Exception {
        AssetId assetId = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        doThrow(new IllegalArgumentException("Device " + deviceId.value() + " does not belong to asset " + assetId.value()))
                .when(assetService).unassignDevice(eq(assetId), eq(deviceId), any());

        mockMvc.perform(delete("/api/assets/{id}/devices/{deviceId}", assetId.value(), deviceId.value()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void unassignDeviceReturns404ForAnUnknownAsset() throws Exception {
        AssetId unknown = AssetId.random();
        DeviceId deviceId = DeviceId.random();
        doThrow(new NoSuchElementException("Unknown asset: " + unknown.value()))
                .when(assetService).unassignDevice(eq(unknown), eq(deviceId), any());

        mockMvc.perform(delete("/api/assets/{id}/devices/{deviceId}", unknown.value(), deviceId.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
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
    void detailsIncludesHasImageFromTheImageRepository() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);
        AssetSummary summary = new AssetSummary(asset, "Drone", AssetStatus.OFFLINE, null, null);
        when(assetService.details(asset.id())).thenReturn(new AssetDetails(summary, List.of(device), List.of()));
        when(assetImageRepositoryPort.existsByAssetId(asset.id())).thenReturn(true);

        mockMvc.perform(get("/api/assets/{id}", asset.id().value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImage").value(true));
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
    void startStreamReturns201WithWhepUrlWhenPublisherHasOne() throws Exception {
        Device device = videoDevice();
        Asset asset = asset(device);

        StreamId streamId = StreamId.random();
        when(assetService.startStream(eq(asset.id()), isNull(), any())).thenReturn(streamId);
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
        when(assetService.startStream(eq(asset.id()), isNull(), any())).thenReturn(streamId);
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
                .andExpect(jsonPath("$[0].deviceId").value(deviceId.value().toString()))
                .andExpect(jsonPath("$[0].at").value("2026-07-20T10:00:00Z"))
                .andExpect(jsonPath("$[0].latitude").value(50.45))
                .andExpect(jsonPath("$[0].longitude").value(30.52))
                .andExpect(jsonPath("$[0].altitudeMeters").value(120.0))
                .andExpect(jsonPath("$[0].headingDegrees").value(90.0))
                .andExpect(jsonPath("$[0].batteryPercent").value(87.5));

        verify(telemetryRepositoryPort).findByUsage(usageId, 100);
    }

    @Test
    void telemetryOmitsFlightStateAndExtraWhenTheSampleCarriesNeither() throws Exception {
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        Telemetry sample = new Telemetry(deviceId, Instant.parse("2026-07-20T10:00:00Z"), 50.45, 30.52, 120.0, 90.0,
                87.5, Map.of());
        when(telemetryRepositoryPort.findByUsage(usageId, 100)).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightState").doesNotExist())
                .andExpect(jsonPath("$[0].extra").doesNotExist());
    }

    @Test
    void telemetryIncludesFlightStateAndExtraWhenTheSampleCarriesThem() throws Exception {
        // docs/FC-INTEGRATIONS-PLAN.md F-b: FlightStateResponse mirrors the frozen wire contract
        // field-for-field, and per-field nullability (only some FlightState fields known here)
        // must survive the DTO mapping honestly rather than fabricating the rest.
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        FlightState flightState = new FlightState("ardupilot", "RTL", true, true, 3, 12, 0.9, 87,
                List.of("Arm: Compass not calibrated"));
        Telemetry sample = new Telemetry(deviceId, Instant.parse("2026-07-20T10:00:00Z"), 50.45, 30.52, 120.0, 90.0,
                87.5, Map.of("groundspeedMps", 12.3), flightState);
        when(telemetryRepositoryPort.findByUsage(usageId, 100)).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightState.firmware").value("ardupilot"))
                .andExpect(jsonPath("$[0].flightState.mode").value("RTL"))
                .andExpect(jsonPath("$[0].flightState.armed").value(true))
                .andExpect(jsonPath("$[0].flightState.failsafe").value(true))
                .andExpect(jsonPath("$[0].flightState.gpsFixType").value(3))
                .andExpect(jsonPath("$[0].flightState.satellites").value(12))
                .andExpect(jsonPath("$[0].flightState.hdop").value(0.9))
                .andExpect(jsonPath("$[0].flightState.rssiPercent").value(87))
                .andExpect(jsonPath("$[0].flightState.armingBlockers", hasSize(1)))
                .andExpect(jsonPath("$[0].flightState.armingBlockers[0]").value("Arm: Compass not calibrated"))
                .andExpect(jsonPath("$[0].extra.groundspeedMps").value(12.3));
    }

    @Test
    void telemetryOmitsFlightStateWhenPresentButAllFieldsUnknownAndArmingBlockersEmpty() throws Exception {
        UsageId usageId = UsageId.random();
        DeviceId deviceId = DeviceId.random();
        Telemetry sample = new Telemetry(deviceId, Instant.parse("2026-07-20T10:00:00Z"), null, null, null, null,
                null, Map.of(), FlightState.empty());
        when(telemetryRepositoryPort.findByUsage(usageId, 100)).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightState").exists())
                .andExpect(jsonPath("$[0].flightState.firmware").doesNotExist())
                .andExpect(jsonPath("$[0].flightState.armingBlockers").doesNotExist());
    }

    @Test
    void telemetryDistinguishesSamplesFromDifferentDeviceIds() throws Exception {
        // docs/CYCLES-PLAN.md §11, CD-a: deviceId is what makes samples from two different
        // telemetry devices on the same usage/asset distinguishable.
        UsageId usageId = UsageId.random();
        DeviceId deviceA = DeviceId.random();
        DeviceId deviceB = DeviceId.random();
        Telemetry sampleA = new Telemetry(deviceA, Instant.parse("2026-07-20T10:00:00Z"), 1.0, 2.0, null, null, null,
                Map.of());
        Telemetry sampleB = new Telemetry(deviceB, Instant.parse("2026-07-20T10:00:01Z"), 3.0, 4.0, null, null, null,
                Map.of());
        when(telemetryRepositoryPort.findByUsage(usageId, 100)).thenReturn(List.of(sampleA, sampleB));

        mockMvc.perform(get("/api/usages/{usageId}/telemetry", usageId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].deviceId").value(deviceA.value().toString()))
                .andExpect(jsonPath("$[1].deviceId").value(deviceB.value().toString()));
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
