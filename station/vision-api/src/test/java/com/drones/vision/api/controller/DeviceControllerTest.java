package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.PrincipalResolver;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.map.application.MapAccessPolicy;
import com.drones.vision.warehouse.application.device.DeviceEdit;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.kernel.Capability;
import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import com.drones.vision.api.security.CurrentUser;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceControllerTest {

    private DeviceService deviceService;
    /** Backs {@link StreamAccess}'s device&rarr;asset&rarr;owner resolution (LIVE-SCOPE W5). */
    private AssetRepositoryPort assetRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    /** Unbounded (auth-off-equivalent) by default, so every pre-existing test below is unaffected. */
    private final CurrentUser currentUser = new CurrentUser(ownership);
    private final DeviceId deviceId = DeviceId.random();
    /** The asset {@link #deviceId} belongs to, for the authority tests near the end of this file. */
    private final Asset ownedAsset = new Asset(AssetId.random(), "my drone", new CategoryId("drone"), ownership,
            Set.of(deviceId), Map.of());
    /** An asset the PILOT authority tests below are deliberately NOT scoped to. */
    private final AssetId otherAssetId = AssetId.random();

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        assetRepositoryPort = mock(AssetRepositoryPort.class);
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.of(ownedAsset));
        mockMvc = mockMvcFor(currentUser);
    }

    /**
     * A {@link CurrentUser} answering with {@link #ownership}/{@link #ownerId} (so stubs keyed on
     * them keep working) but a caller-supplied {@link VisibilityScope} — the same idiom {@code
     * StreamControllerTest#currentUserWithScope} uses. {@link PrincipalResolver#viewer()} is never
     * called by {@link DeviceController}/{@link StreamAccess}, so it throws rather than fake a map
     * viewer no test here needs.
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
                throw new UnsupportedOperationException("DeviceController never calls viewer()");
            }
        });
    }

    /**
     * A {@link MockMvc} bound to a fresh {@link DeviceController} acting as {@code user} — same
     * mocked {@link #deviceService}/{@link #assetRepositoryPort}, only the {@link StreamAccess}'s
     * {@link CurrentUser} changes.
     */
    private MockMvc mockMvcFor(CurrentUser user) {
        StreamAccess streamAccess = new StreamAccess(mock(StreamService.class), assetRepositoryPort, user);
        return MockMvcBuilders.standaloneSetup(new DeviceController(deviceService, user, streamAccess))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static Device device() {
        return new Device(DeviceId.random(), "cam-1", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()));
    }

    @Test
    void registerReturns201WithDeviceResponseAndDefaultsCapabilitiesToVideo() throws Exception {
        Device saved = device();
        when(deviceService.register(any(), any())).thenReturn(saved);

        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1"}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(saved.id().value().toString()))
                .andExpect(jsonPath("$.name").value("cam-1"))
                .andExpect(jsonPath("$.capabilities", hasSize(1)))
                .andExpect(jsonPath("$.capabilities[0]").value("VIDEO"))
                .andExpect(jsonPath("$.protocol").value("sim"))
                .andExpect(jsonPath("$.uri").value("sim://cam-1"));

        ArgumentCaptor<DeviceRegistration> captor =
                ArgumentCaptor.forClass(DeviceRegistration.class);
        verify(deviceService).register(captor.capture(), any());
        assertEquals(Set.of(Capability.VIDEO), captor.getValue().capabilities());
        assertEquals("cam-1", captor.getValue().name());
    }

    @Test
    void registerAcceptsExplicitCapabilitiesAndMapsThemThroughToRegistration() throws Exception {
        Device saved = device();
        when(deviceService.register(any(), any())).thenReturn(saved);

        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1","capabilities":["VIDEO","TELEMETRY"]}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<DeviceRegistration> captor =
                ArgumentCaptor.forClass(DeviceRegistration.class);
        verify(deviceService).register(captor.capture(), any());
        assertEquals(Set.of(Capability.VIDEO, Capability.TELEMETRY), captor.getValue().capabilities());
    }

    @Test
    void registerAcceptsLowerCaseCapabilityNames() throws Exception {
        Device saved = device();
        when(deviceService.register(any(), any())).thenReturn(saved);

        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1","capabilities":["telemetry"]}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<DeviceRegistration> captor =
                ArgumentCaptor.forClass(DeviceRegistration.class);
        verify(deviceService).register(captor.capture(), any());
        assertEquals(Set.of(Capability.TELEMETRY), captor.getValue().capabilities());
    }

    @Test
    void registerReturns400ForUnknownCapability() throws Exception {
        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1","capabilities":["LASER"]}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value(
                        "Unknown capability: LASER (valid values: VIDEO, TELEMETRY, PTZ, AUDIO)"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void registerAcceptsOptionalOptions() throws Exception {
        Device saved = device();
        when(deviceService.register(any(), any())).thenReturn(saved);

        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1","options":{"loop":"true"}}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        ArgumentCaptor<DeviceRegistration> captor =
                ArgumentCaptor.forClass(DeviceRegistration.class);
        verify(deviceService).register(captor.capture(), any());
        assertEquals(Map.of("loop", "true"), captor.getValue().stream().options());
    }

    @Test
    void listReturns200WithDevices() throws Exception {
        when(deviceService.devices(false)).thenReturn(List.of(device(), device()));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void listReturns200WithEmptyListWhenNoDevices() throws Exception {
        when(deviceService.devices(false)).thenReturn(List.of());

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void registerReturns400ForBlankName() throws Exception {
        String body = """
                {"name":"","protocol":"sim","uri":"sim://cam-1"}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void registerReturns400ForBlankProtocol() throws Exception {
        String body = """
                {"name":"cam-1","protocol":"","uri":"sim://cam-1"}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void registerReturns400ForUpperCaseProtocolRejectedByDomain() throws Exception {
        // StreamDescriptor requires a lower-case protocol key; the domain-level
        // IllegalArgumentException surfaces through the same 400 mapping.
        String body = """
                {"name":"cam-1","protocol":"SIM","uri":"sim://cam-1"}
                """;

        mockMvc.perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    // ---- GET /api/devices?includeDeleted= ----

    @Test
    void listPassesIncludeDeletedThroughToTheService() throws Exception {
        Device deleted = device().withState(LifecycleState.DELETED);
        when(deviceService.devices(true)).thenReturn(List.of(device(), deleted));

        mockMvc.perform(get("/api/devices").param("includeDeleted", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].state").value("DELETED"));

        verify(deviceService).devices(true);
    }

    // ---- PATCH /api/devices/{id} ----

    @Test
    void updateAppliesTheEditAndReturns200WithDeviceResponse() throws Exception {
        DeviceId id = DeviceId.random();
        Device updated = new Device(id, "renamed", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()));
        when(deviceService.update(eq(id), any(), any())).thenReturn(updated);

        String body = """
                {"name":"renamed"}
                """;

        mockMvc.perform(patch("/api/devices/{id}", id.value()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.value().toString()))
                .andExpect(jsonPath("$.name").value("renamed"));

        ArgumentCaptor<DeviceEdit> captor = ArgumentCaptor.forClass(DeviceEdit.class);
        verify(deviceService).update(eq(id), captor.capture(), any());
        assertEquals("renamed", captor.getValue().name());
    }

    @Test
    void updateReturns400WhenProtocolIsSentWithoutUri() throws Exception {
        String body = """
                {"protocol":"rtsp"}
                """;

        mockMvc.perform(patch("/api/devices/{id}", DeviceId.random().value())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void updateReturns404ForUnknownDevice() throws Exception {
        DeviceId unknown = DeviceId.random();
        when(deviceService.update(eq(unknown), any(), any()))
                .thenThrow(new NoSuchElementException("Unknown device: " + unknown.value()));

        mockMvc.perform(patch("/api/devices/{id}", unknown.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"x\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- POST /api/devices/{id}/state ----

    @Test
    void setStateMovesToDeactivatedAndReturns200() throws Exception {
        DeviceId id = DeviceId.random();
        Device deactivated = device().withState(LifecycleState.DEACTIVATED);
        when(deviceService.setState(eq(id), eq(LifecycleState.DEACTIVATED), any())).thenReturn(deactivated);

        mockMvc.perform(post("/api/devices/{id}/state", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DEACTIVATED"));
    }

    @Test
    void setStateAcceptsLowerCaseStateNames() throws Exception {
        DeviceId id = DeviceId.random();
        when(deviceService.setState(eq(id), eq(LifecycleState.ACTIVE), any())).thenReturn(device());

        mockMvc.perform(post("/api/devices/{id}/state", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"active\"}"))
                .andExpect(status().isOk());

        verify(deviceService).setState(eq(id), eq(LifecycleState.ACTIVE), any());
    }

    @Test
    void setStateReturns400ForAnUnrecognizedStateValueListingValidOnes() throws Exception {
        mockMvc.perform(post("/api/devices/{id}/state", DeviceId.random().value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DELETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unknown state: DELETED (valid values: ACTIVE, DEACTIVATED)"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void setStateDeactivatedOnADeletedDeviceRestoresItPerTheContract() throws Exception {
        DeviceId id = DeviceId.random();
        Device restored = device().withState(LifecycleState.DEACTIVATED);
        when(deviceService.setState(eq(id), eq(LifecycleState.DEACTIVATED), any())).thenReturn(restored);

        mockMvc.perform(post("/api/devices/{id}/state", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DEACTIVATED"));
    }

    @Test
    void setStateActiveOnADeletedDeviceReturns409() throws Exception {
        DeviceId id = DeviceId.random();
        when(deviceService.setState(eq(id), eq(LifecycleState.ACTIVE), any()))
                .thenThrow(new IllegalStateException("Source cam-1 is deleted; restore it before putting it back into service"));

        mockMvc.perform(post("/api/devices/{id}/state", id.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONFLICT"));
    }

    @Test
    void setStateReturns404ForUnknownDevice() throws Exception {
        DeviceId unknown = DeviceId.random();
        when(deviceService.setState(eq(unknown), any(), any()))
                .thenThrow(new NoSuchElementException("Unknown device: " + unknown.value()));

        mockMvc.perform(post("/api/devices/{id}/state", unknown.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ---- DELETE /api/devices/{id} ----

    @Test
    void deleteReturns200WithTheDeviceInItsDeletedState() throws Exception {
        DeviceId id = DeviceId.random();
        Device deleted = device().withState(LifecycleState.DELETED);
        when(deviceService.delete(eq(id), any())).thenReturn(deleted);

        mockMvc.perform(delete("/api/devices/{id}", id.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DELETED"));
    }

    @Test
    void deleteIsIdempotentPerTheServiceContract() throws Exception {
        DeviceId id = DeviceId.random();
        Device alreadyDeleted = device().withState(LifecycleState.DELETED);
        when(deviceService.delete(eq(id), any())).thenReturn(alreadyDeleted);

        mockMvc.perform(delete("/api/devices/{id}", id.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DELETED"));

        mockMvc.perform(delete("/api/devices/{id}", id.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DELETED"));
    }

    // ---- docs/plans/done/LIVE-SCOPE-PLAN.md §2.2, W5: authority --------------------------------
    //
    // Every test above runs under the class-level `currentUser` (unbounded scope, matching how a
    // deployment with `vision.auth.enabled=false` behaves today) and is untouched by this wave --
    // that is the "default-config suites stay green" bar. The tests below are the ones that fail
    // before this wave: register/delete require org-level management authority; update/setState
    // require the caller be able to reach the device's owning asset (a PILOT's own assigned device
    // keeps working); list is filtered, not all-or-nothing 403'd.

    private MockMvc pilotScopedTo(AssetId assetId) {
        return mockMvcFor(currentUserWithScope(VisibilityScope.assignedAssets(Set.of(assetId))));
    }

    private MockMvc managerScopedTo(GroupId groupId) {
        return mockMvcFor(currentUserWithScope(VisibilityScope.groups(Set.of(groupId))));
    }

    // ---- register/delete: canManageOrg() ----

    @Test
    void registerReturns403ForAPilotScope() throws Exception {
        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1"}
                """;

        pilotScopedTo(ownedAsset.id())
                .perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verifyNoInteractions(deviceService);
    }

    @Test
    void registerSucceedsForAManagerScope() throws Exception {
        Device saved = device();
        when(deviceService.register(any(), any())).thenReturn(saved);
        String body = """
                {"name":"cam-1","protocol":"sim","uri":"sim://cam-1"}
                """;

        managerScopedTo(ownership.groupId())
                .perform(post("/api/devices").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteReturns403ForAPilotScopeEvenOnTheirOwnAssignedDevice() throws Exception {
        // Registering/deleting a device row is fleet administration, not a per-asset operation --
        // see DeviceController's own javadoc for why this differs from update/setState below.
        pilotScopedTo(ownedAsset.id())
                .perform(delete("/api/devices/{id}", deviceId.value()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(deviceService, never()).delete(any(), any());
    }

    @Test
    void deleteSucceedsForAManagerScope() throws Exception {
        Device deleted = device().withState(LifecycleState.DELETED);
        when(deviceService.delete(eq(deviceId), any())).thenReturn(deleted);

        managerScopedTo(ownership.groupId())
                .perform(delete("/api/devices/{id}", deviceId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("DELETED"));
    }

    // ---- update/setState: reach the device's owning asset ----

    @Test
    void updateReturns200ForAPilotAssignedToTheDevicesOwnAsset() throws Exception {
        Device updated = new Device(deviceId, "renamed", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()));
        when(deviceService.update(eq(deviceId), any(), any())).thenReturn(updated);

        pilotScopedTo(ownedAsset.id())
                .perform(patch("/api/devices/{id}", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed"));
    }

    @Test
    void updateReturns404ForAPilotAssignedToADifferentAsset() throws Exception {
        pilotScopedTo(otherAssetId)
                .perform(patch("/api/devices/{id}", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verify(deviceService, never()).update(any(), any(), any());
    }

    @Test
    void updateSucceedsForAManagerScopeInTheAssetsSubtree() throws Exception {
        Device updated = new Device(deviceId, "renamed", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()));
        when(deviceService.update(eq(deviceId), any(), any())).thenReturn(updated);

        managerScopedTo(ownership.groupId())
                .perform(patch("/api/devices/{id}", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void updateReturns404ForAManagerScopeOutsideTheAssetsSubtree() throws Exception {
        managerScopedTo(GroupId.random())
                .perform(patch("/api/devices/{id}", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"renamed\"}"))
                .andExpect(status().isNotFound());

        verify(deviceService, never()).update(any(), any(), any());
    }

    @Test
    void setStateReturns200ForAPilotAssignedToTheDevicesOwnAsset() throws Exception {
        Device deactivated = device().withState(LifecycleState.DEACTIVATED);
        when(deviceService.setState(eq(deviceId), eq(LifecycleState.DEACTIVATED), any())).thenReturn(deactivated);

        pilotScopedTo(ownedAsset.id())
                .perform(post("/api/devices/{id}/state", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void setStateReturns404ForAPilotAssignedToADifferentAsset() throws Exception {
        pilotScopedTo(otherAssetId)
                .perform(post("/api/devices/{id}/state", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isNotFound());

        verify(deviceService, never()).setState(any(), any(), any());
    }

    @Test
    void setStateReturns404ForAPilotWhenTheDeviceBelongsToNoAssetAtAll() throws Exception {
        // An unowned device is a fleet-administration concern, not any one PILOT's -- StreamAccess's
        // own pre-existing ruling (LIVE-SCOPE W2), reused rather than re-decided here.
        DeviceId unowned = DeviceId.random();
        when(assetRepositoryPort.findByDeviceId(unowned)).thenReturn(Optional.empty());

        pilotScopedTo(ownedAsset.id())
                .perform(post("/api/devices/{id}/state", unowned.value())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"state\":\"DEACTIVATED\"}"))
                .andExpect(status().isNotFound());
    }

    // ---- list: filtered, not 403'd ----

    @Test
    void listFiltersOutDevicesThePilotsScopeCannotReach() throws Exception {
        Device otherDevice = device();
        Asset otherAsset = new Asset(otherAssetId, "someone else's drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(otherDevice.id()), Map.of());
        when(assetRepositoryPort.findByDeviceId(otherDevice.id())).thenReturn(Optional.of(otherAsset));
        Device ownDevice = new Device(deviceId, "cam-1", Set.of(Capability.VIDEO),
                new StreamDescriptor("sim", URI.create("sim://cam-1"), Map.of()));
        when(deviceService.devices(false)).thenReturn(List.of(ownDevice, otherDevice));

        pilotScopedTo(ownedAsset.id()).perform(get("/api/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(deviceId.value().toString()));
    }
}
