package com.drones.vision.api.controller;

import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.security.StreamAccess;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.perception.application.stream.StreamService;
import com.drones.vision.platform.Authority;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.application.pairing.PairingService;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.model.RadioBind;
import com.drones.vision.warehouse.domain.model.VehicleKey;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigInteger;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@link PairingController} (docs/plans/active/LINK-PAIRING-PLAN.md §3.3) — same
 * standalone-{@link MockMvc} + mocked-service-layer idiom as {@link DeviceControllerTest}, whose
 * {@link StreamAccess}/{@link CurrentUser} authority split {@link PairingController} deliberately
 * mirrors (device-keyed endpoints gated by {@link StreamAccess#requireVisible(DeviceId)}, the
 * org-wide list gated by {@link com.drones.vision.platform.Authority#mayManageOrg()}).
 */
class PairingControllerTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private PairingService pairingService;
    private DeviceService deviceService;
    private AssetRepositoryPort assetRepositoryPort;
    private MockMvc mockMvc;

    private final UserId ownerId = UserId.random();
    private final Ownership ownership = new Ownership(ownerId, GroupId.random());
    private final CurrentUser currentUser = new CurrentUser(ownership);
    private final DeviceId deviceId = DeviceId.random();

    @BeforeEach
    void setUp() {
        pairingService = mock(PairingService.class);
        deviceService = mock(DeviceService.class);
        assetRepositoryPort = mock(AssetRepositoryPort.class);
        // No asset owns deviceId in these tests -- StreamAccess#requireVisible(DeviceId) falls back
        // to mayManageOrg() for an unowned device, same rule DeviceControllerTest's own unowned-device
        // tests rely on, so an unbounded CurrentUser (mayManageOrg() == true) always passes.
        when(assetRepositoryPort.findByDeviceId(deviceId)).thenReturn(Optional.empty());
        mockMvc = mockMvcFor(currentUser);
    }

    private MockMvc mockMvcFor(CurrentUser user) {
        StreamAccess streamAccess = new StreamAccess(mock(StreamService.class), assetRepositoryPort, user);
        return MockMvcBuilders.standaloneSetup(new PairingController(pairingService, deviceService, streamAccess,
                        user))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    private static VehicleKey vehicleKey() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new VehicleKey(bytes);
    }

    /**
     * {@code hardwareUid} defaults to {@code null} (not yet probed) — the shape most of this
     * class's fixtures need; {@link #pairPassesTheParsedHardwareUidThrough} builds its own {@link
     * Pairing} directly rather than adding a hardwareUid parameter here just for one test.
     */
    private static Pairing pairing(DeviceId deviceId, int sysid) {
        return new Pairing(PairingId.random(), deviceId, sysid, vehicleKey(), null, RadioBind.NONE,
                Instant.parse("2026-09-01T00:00:00Z"), null);
    }

    // --- pair -------------------------------------------------------------------------------

    @Test
    void pairReturns200WithThePairingAndNoSysidPushWhenTheHeardValueIsKept() throws Exception {
        Pairing saved = pairing(deviceId, 42);
        when(pairingService.pair(eq(deviceId), eq(42), eq((BigInteger) null), any())).thenReturn(saved);

        mockMvc.perform(post("/api/devices/{id}/pairing", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardSysid\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pairingId").value(saved.id().value().toString()))
                .andExpect(jsonPath("$.deviceId").value(deviceId.value().toString()))
                .andExpect(jsonPath("$.sysid").value(42))
                .andExpect(jsonPath("$.sysidPushRequired").value(false))
                .andExpect(jsonPath("$.hardwareUid").doesNotExist());
    }

    @Test
    void pairFlagsSysidPushRequiredWhenTheAssignedSysidDiffersFromWhatWasHeard() throws Exception {
        Pairing saved = pairing(deviceId, 11); // collided, assigned a different number
        when(pairingService.pair(eq(deviceId), eq(10), eq((BigInteger) null), any())).thenReturn(saved);

        mockMvc.perform(post("/api/devices/{id}/pairing", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardSysid\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysid").value(11))
                .andExpect(jsonPath("$.sysidPushRequired").value(true));
    }

    @Test
    void pairWithNoBodyDefaultsToAnUnheardSysidAndAlwaysRequiresAPush() throws Exception {
        Pairing saved = pairing(deviceId, 10);
        when(pairingService.pair(eq(deviceId), eq(0), eq((BigInteger) null), any())).thenReturn(saved);

        mockMvc.perform(post("/api/devices/{id}/pairing", deviceId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sysidPushRequired").value(true));
    }

    @Test
    void pairRejectsAMalformedHardwareUidWith400AndNeverCallsTheService() throws Exception {
        mockMvc.perform(post("/api/devices/{id}/pairing", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardSysid\":42,\"hardwareUid\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());

        verify(pairingService, never()).pair(any(), anyInt(), any(), any());
    }

    @Test
    void pairPassesTheParsedHardwareUidThrough() throws Exception {
        Pairing saved = new Pairing(PairingId.random(), deviceId, 42, vehicleKey(), BigInteger.valueOf(123456789L),
                RadioBind.NONE, Instant.parse("2026-09-01T00:00:00Z"), null);
        when(pairingService.pair(eq(deviceId), eq(42), eq(BigInteger.valueOf(123456789L)), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/api/devices/{id}/pairing", deviceId.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardSysid\":42,\"hardwareUid\":\"123456789\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hardwareUid").value("123456789"));
    }

    // --- forget -------------------------------------------------------------------------------

    @Test
    void forgetReturns204AndDelegatesToTheServiceUsingTheResolvedPairingId() throws Exception {
        Pairing existing = pairing(deviceId, 42);
        when(pairingService.find(deviceId)).thenReturn(Optional.of(existing));

        mockMvc.perform(delete("/api/devices/{id}/pairing", deviceId.value()))
                .andExpect(status().isNoContent());

        ArgumentCaptor<PairingId> captor = ArgumentCaptor.forClass(PairingId.class);
        verify(pairingService).forget(captor.capture(), eq(ownerId));
        assertEquals(existing.id(), captor.getValue());
    }

    @Test
    void forgetReturns404WhenTheDeviceHasNoPairing() throws Exception {
        when(pairingService.find(deviceId)).thenReturn(Optional.empty());

        mockMvc.perform(delete("/api/devices/{id}/pairing", deviceId.value()))
                .andExpect(status().isNotFound());
    }

    // --- replaceHardware -------------------------------------------------------------------

    @Test
    void replaceHardwareReturnsTheUpdatedPairingWithNoSysidPushField() throws Exception {
        Pairing existing = pairing(deviceId, 42);
        Pairing replaced = new Pairing(existing.id(), deviceId, 42, existing.vehicleKey(), null, RadioBind.NONE,
                existing.createdAt(), Instant.parse("2026-09-18T00:00:00Z"));
        when(pairingService.find(deviceId)).thenReturn(Optional.of(existing));
        when(pairingService.replaceHardware(existing.id(), ownerId)).thenReturn(replaced);

        mockMvc.perform(post("/api/devices/{id}/pairing/replace-hardware", deviceId.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hardwareUid").doesNotExist())
                .andExpect(jsonPath("$.replacedAt").value("2026-09-18T00:00:00Z"))
                .andExpect(jsonPath("$.sysidPushRequired").doesNotExist());
    }

    @Test
    void replaceHardwareReturns404WhenTheDeviceHasNoPairing() throws Exception {
        when(pairingService.find(deviceId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/devices/{id}/pairing/replace-hardware", deviceId.value()))
                .andExpect(status().isNotFound());
    }

    // --- unpairedDevices ---------------------------------------------------------------------

    @Test
    void unpairedDevicesListsDevicesFromListUnpairedEnrichedWithNameAndCapabilities() throws Exception {
        Device device = new Device(deviceId, "vehicle-7", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:14550"), Map.of()));
        when(pairingService.listUnpaired()).thenReturn(List.of(deviceId));
        when(deviceService.find(deviceId)).thenReturn(Optional.of(device));

        mockMvc.perform(get("/api/pairings/unpaired-devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value(deviceId.value().toString()))
                .andExpect(jsonPath("$[0].name").value("vehicle-7"))
                .andExpect(jsonPath("$[0].capabilities[0]").value("TELEMETRY"));
    }

    @Test
    void unpairedDevicesReturns403ForACallerWhoMayNotManageTheOrg() throws Exception {
        CurrentUser restricted = new CurrentUser(new com.drones.vision.api.security.PrincipalResolver() {
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
                return VisibilityScope.unbounded();
            }

            @Override
            public com.drones.vision.map.application.MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public com.drones.vision.identity.domain.model.Role role() {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public Authority authority() {
                return new Authority(VisibilityScope.unbounded(), EnumSet.noneOf(com.drones.vision.platform.Capability.class));
            }
        });

        mockMvcFor(restricted).perform(get("/api/pairings/unpaired-devices"))
                .andExpect(status().isForbidden());
    }

    // --- authority (device-keyed endpoints require visibility, not org management) ---------

    @Test
    void pairReturns404WhenTheCallerCannotSeeTheDevice() throws Exception {
        // A device already owned by an asset the caller's scope excludes.
        DeviceId scopedOutDevice = DeviceId.random();
        com.drones.vision.kernel.AssetId assetId = com.drones.vision.kernel.AssetId.random();
        com.drones.vision.warehouse.domain.model.Asset asset = com.drones.vision.warehouse.domain.model.Asset
                .register(assetId, "someone else's drone", new com.drones.vision.kernel.CategoryId("drone"),
                        new Ownership(UserId.random(), GroupId.random()), Set.of(scopedOutDevice), Map.of(),
                        com.drones.vision.warehouse.domain.model.Identity.NONE,
                        com.drones.vision.warehouse.domain.model.Custody.NONE);
        when(assetRepositoryPort.findByDeviceId(scopedOutDevice)).thenReturn(Optional.of(asset));
        VisibilityScope emptyScope = VisibilityScope.groups(Set.of(GroupId.random()));
        CurrentUser scopedUser = new CurrentUser(new com.drones.vision.api.security.PrincipalResolver() {
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
                return emptyScope;
            }

            @Override
            public com.drones.vision.map.application.MapAccessPolicy.Viewer viewer() {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public com.drones.vision.identity.domain.model.Role role() {
                throw new UnsupportedOperationException("not needed by this test");
            }

            @Override
            public Authority authority() {
                return new Authority(emptyScope, EnumSet.noneOf(com.drones.vision.platform.Capability.class));
            }
        });

        mockMvcFor(scopedUser).perform(post("/api/devices/{id}/pairing", scopedOutDevice.value())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"heardSysid\":42}"))
                .andExpect(status().isNotFound());
    }
}
