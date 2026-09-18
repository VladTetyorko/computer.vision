package com.drones.vision.warehouse.application.pairing;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.DeviceOrigin;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.port.PairingRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultPairingService} (docs/plans/active/LINK-PAIRING-PLAN.md §3.3/§7).
 *
 * <p>{@link PairingRepositoryPort} is a genuine hand-written in-memory fake ({@link
 * FakePairingRepository}) rather than a Mockito mock: the sysid-collision logic probes {@code
 * findBySysid} across the whole configured range, which is realistic to express as a small
 * in-memory table but awkward to stub call-by-call. {@link DeviceService} is a plain Mockito mock —
 * {@link #listUnpaired} is its only caller here.
 */
class DefaultPairingServiceTest {

    private static final StreamDescriptor MAVLINK =
            new StreamDescriptor("mavlink", URI.create("udp://0.0.0.0:14550"), Map.of());

    private FakePairingRepository pairingRepository;
    private DeviceService deviceService;
    private AuditTrailPort auditTrail;
    private PairingService service;
    private final UserId actor = UserId.random();

    @BeforeEach
    void setUp() {
        pairingRepository = new FakePairingRepository();
        deviceService = mock(DeviceService.class);
        auditTrail = mock(AuditTrailPort.class);
        service = new DefaultPairingService(pairingRepository, deviceService, auditTrail,
                new PairingSettings(10, 250));
    }

    // --- pair: heard sysid kept when free -------------------------------------

    @Test
    void heardSysidIsKeptWhenNoOtherPairingHoldsIt() {
        DeviceId deviceId = DeviceId.random();

        Pairing pairing = service.pair(deviceId, 42, null, actor);

        assertEquals(42, pairing.sysid());
        assertEquals(deviceId, pairing.deviceId());
        assertNotNull(pairing.id());
        assertNotNull(pairing.vehicleKey());
        assertEquals(AuditTargetType.PAIRING, recordedAudit().targetType());
    }

    @Test
    void pairMintsAThirtyTwoByteVehicleKey() {
        Pairing pairing = service.pair(DeviceId.random(), 42, null, actor);

        assertEquals(32, pairing.vehicleKey().value().length);
    }

    @Test
    void pairIsIdempotentForAnAlreadyPairedDevice() {
        DeviceId deviceId = DeviceId.random();
        Pairing first = service.pair(deviceId, 42, null, actor);

        Pairing second = service.pair(deviceId, 99, null, actor);

        assertEquals(first.id(), second.id());
        assertEquals(first.sysid(), second.sysid());
        assertEquals(1, pairingRepository.findAll().size());
    }

    // --- pair: collision assigns the lowest free number in range -------------

    @Test
    void collidingHeardSysidGetsTheLowestFreeNumberInRange() {
        service.pair(DeviceId.random(), 10, null, actor);
        service.pair(DeviceId.random(), 11, null, actor);

        Pairing third = service.pair(DeviceId.random(), 10, null, actor);

        assertEquals(12, third.sysid());
    }

    @Test
    void lowestFreeSysidIsReclaimedAfterForget() {
        DeviceId first = DeviceId.random();
        DeviceId second = DeviceId.random();
        Pairing p1 = service.pair(first, 10, null, actor);
        service.pair(second, 10, null, actor); // collides -> takes 11

        service.forget(p1.id(), actor);

        // 10 is free again; a fresh device heard at 10 should get 10 back, not skip to 12.
        Pairing third = service.pair(DeviceId.random(), 10, null, actor);
        assertEquals(10, third.sysid());
    }

    @Test
    void aHeardSysidOutsideTheConfiguredRangeIsTreatedAsUnavailable() {
        Pairing pairing = service.pair(DeviceId.random(), 1, null, actor); // factory default, below range min

        assertEquals(10, pairing.sysid());
    }

    // --- replaceHardware -------------------------------------------------------

    @Test
    void replaceHardwareClearsUidBumpsReplacedAtAndKeepsSysidAndKey() {
        Pairing original = service.pair(DeviceId.random(), 42, BigInteger.valueOf(123456789L), actor);
        assertNotNull(original.hardwareUid());
        assertNull(original.replacedAt());

        Pairing replaced = service.replaceHardware(original.id(), actor);

        assertNull(replaced.hardwareUid());
        assertNotNull(replaced.replacedAt());
        assertEquals(original.sysid(), replaced.sysid());
        assertEquals(original.vehicleKey(), replaced.vehicleKey());
        assertEquals(original.id(), replaced.id());
        assertEquals(original.deviceId(), replaced.deviceId());
    }

    @Test
    void replaceHardwareThrowsForUnknownPairing() {
        assertThrows(NoSuchElementException.class,
                () -> service.replaceHardware(PairingId.random(), actor));
    }

    // --- forget (hard delete) ---------------------------------------------------

    @Test
    void forgetHardDeletesThePairingRow() {
        Pairing pairing = service.pair(DeviceId.random(), 42, null, actor);

        service.forget(pairing.id(), actor);

        assertTrue(pairingRepository.findById(pairing.id()).isEmpty());
        assertTrue(service.find(pairing.deviceId()).isEmpty());
    }

    // --- listUnpaired ------------------------------------------------------------

    @Test
    void listUnpairedExcludesDevicesThatAlreadyHaveAPairing() {
        Device paired = device("paired");
        Device unpaired = device("unpaired");
        when(deviceService.devices(false)).thenReturn(List.of(paired, unpaired));
        service.pair(paired.id(), 42, null, actor);

        List<DeviceId> result = service.listUnpaired();

        assertEquals(List.of(unpaired.id()), result);
    }

    private static Device device(String name) {
        return new Device(DeviceId.random(), name, Set.of(Capability.TELEMETRY), MAVLINK,
                LifecycleState.ACTIVE, DeviceOrigin.LIVE);
    }

    private AuditEntry recordedAudit() {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        org.mockito.Mockito.verify(auditTrail, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    /** Genuine in-memory fake — see class javadoc for why this is not a Mockito mock. */
    private static final class FakePairingRepository implements PairingRepositoryPort {

        private final Map<PairingId, Pairing> byId = new LinkedHashMap<>();

        @Override
        public Optional<Pairing> findById(PairingId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Pairing> findByDeviceId(DeviceId deviceId) {
            return byId.values().stream().filter(p -> p.deviceId().equals(deviceId)).findFirst();
        }

        @Override
        public Optional<Pairing> findBySysid(int sysid) {
            return byId.values().stream().filter(p -> p.sysid() == sysid).findFirst();
        }

        @Override
        public List<Pairing> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public Pairing save(Pairing pairing) {
            byId.put(pairing.id(), pairing);
            return pairing;
        }

        @Override
        public void deleteById(PairingId id) {
            byId.remove(id);
        }
    }
}
