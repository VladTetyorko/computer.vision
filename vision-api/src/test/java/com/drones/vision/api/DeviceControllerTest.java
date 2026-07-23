package com.drones.vision.api;

import com.drones.vision.application.DeviceEdit;
import com.drones.vision.application.DeviceRegistration;
import com.drones.vision.application.DeviceService;
import com.drones.vision.domain.model.Capability;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LifecycleState;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.StreamDescriptor;
import com.drones.vision.domain.model.UserId;
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
import java.util.Set;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

class DeviceControllerTest {

    private DeviceService deviceService;
    private MockMvc mockMvc;

    private final CurrentUser currentUser = new CurrentUser(new Ownership(UserId.random(), GroupId.random()));

    @BeforeEach
    void setUp() {
        deviceService = mock(DeviceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceController(deviceService, currentUser))
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
}
