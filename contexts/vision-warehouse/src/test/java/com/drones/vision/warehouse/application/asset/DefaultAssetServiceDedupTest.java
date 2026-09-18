package com.drones.vision.warehouse.application.asset;

import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.LifecycleState;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditTrailPort;
import com.drones.vision.warehouse.application.device.DeviceEdit;
import com.drones.vision.warehouse.application.device.DeviceRegistration;
import com.drones.vision.warehouse.application.device.DeviceService;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetLiveStatePort;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.AssetUsageRepositoryPort;
import com.drones.vision.warehouse.domain.port.CategoryRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Identity-first dedup tests for {@link DefaultAssetService#findDuplicateDevice} (docs/plans/active/
 * LINK-PAIRING-PLAN.md §3.3): a MAVLink sysid is the vehicle's identity, never its address, so a
 * device re-heard at a new {@code uri} must resolve to the <em>same</em> device with its stored
 * address updated in place — never a second device. A test that only counts devices before/after
 * would be vacuous (the task's own warning): {@link #sameSysidAtANewAddressUpdatesTheSameDeviceAndTheOldAddressIsGone}
 * asserts the old address is no longer reachable through {@link DeviceService#devices}, not merely
 * that the count stayed at one.
 *
 * <p>{@link DeviceService} is a genuine hand-written in-memory fake ({@link FakeDeviceService})
 * rather than a Mockito mock, precisely so a real {@code update} call's effect is observable on a
 * later {@code devices(false)} read — the exact thing this test exists to prove.
 */
class DefaultAssetServiceDedupTest {

    private FakeDeviceService deviceService;
    private AssetRepositoryPort assetRepository;
    private DefaultAssetService service;

    @BeforeEach
    void setUp() {
        deviceService = new FakeDeviceService();
        assetRepository = mock(AssetRepositoryPort.class);
        CategoryRepositoryPort categoryRepository = mock(CategoryRepositoryPort.class);
        AssetUsageRepositoryPort usageRepository = mock(AssetUsageRepositoryPort.class);
        AuditTrailPort auditTrail = mock(AuditTrailPort.class);
        AssetLiveStatePort assetLiveStatePort = mock(AssetLiveStatePort.class);
        when(assetLiveStatePort.activeStreamsByDevice()).thenReturn(Map.of());
        when(assetRepository.findByDeviceId(any())).thenReturn(Optional.empty());

        service = new DefaultAssetService(assetRepository, categoryRepository, usageRepository, auditTrail,
                deviceService, assetLiveStatePort);
    }

    @Test
    void sameSysidAtANewAddressUpdatesTheSameDeviceAndTheOldAddressIsGone() {
        StreamDescriptor oldAddress =
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "7"));
        Device original = deviceService.seed("vehicle-7", Set.of(Capability.TELEMETRY), oldAddress);

        StreamDescriptor newAddress =
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.99:19999"), Map.of("sysid", "7"));
        Optional<DuplicateDeviceMatch> match = service.findDuplicateDevice(newAddress);

        // One device, the same one -- never a second device forked for the new address.
        assertTrue(match.isPresent());
        assertEquals(original.id(), match.get().deviceId());
        assertEquals(1, deviceService.devices(false).size());

        // The old address is genuinely gone, not merely "a device count of one" (which a
        // fixed-size mock would satisfy vacuously even if the address were never updated).
        Device stored = deviceService.find(original.id()).orElseThrow();
        assertEquals(newAddress, stored.stream());
        assertFalse(deviceService.devices(false).stream()
                .anyMatch(d -> d.stream().uri().equals(oldAddress.uri())));
    }

    @Test
    void reportingTheSameAddressAgainDoesNotWriteAnUpdate() {
        StreamDescriptor stream =
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "7"));
        deviceService.seed("vehicle-7", Set.of(Capability.TELEMETRY), stream);
        int updatesBefore = deviceService.updateCount();

        service.findDuplicateDevice(stream);

        assertEquals(updatesBefore, deviceService.updateCount());
    }

    @Test
    void aDifferentSysidAtTheOldAddressIsNotTreatedAsTheSameDevice() {
        StreamDescriptor original =
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "7"));
        deviceService.seed("vehicle-7", Set.of(Capability.TELEMETRY), original);

        StreamDescriptor differentVehicleSameWire =
                new StreamDescriptor("mavlink", URI.create("udp://10.0.0.5:14550"), Map.of("sysid", "8"));
        Optional<DuplicateDeviceMatch> match = service.findDuplicateDevice(differentVehicleSameWire);

        assertTrue(match.isEmpty());
    }

    /** Genuine in-memory fake — see class javadoc for why this is not a Mockito mock. */
    private static final class FakeDeviceService implements DeviceService {

        private final Map<DeviceId, Device> byId = new LinkedHashMap<>();
        private int updateCount;

        Device seed(String name, Set<Capability> capabilities, StreamDescriptor stream) {
            Device device = new Device(DeviceId.random(), name, capabilities, stream);
            byId.put(device.id(), device);
            return device;
        }

        int updateCount() {
            return updateCount;
        }

        @Override
        public Device register(DeviceRegistration registration, UserId actor) {
            Device device = new Device(DeviceId.random(), registration.name(), registration.capabilities(),
                    registration.stream());
            byId.put(device.id(), device);
            return device;
        }

        @Override
        public List<Device> devices(boolean includeDeleted) {
            return byId.values().stream()
                    .filter(d -> includeDeleted || d.state() != LifecycleState.DELETED)
                    .toList();
        }

        @Override
        public Optional<Device> find(DeviceId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Device update(DeviceId id, DeviceEdit edit, UserId actor) {
            Device current = byId.get(id);
            if (current == null) {
                throw new NoSuchElementException("Unknown device: " + id);
            }
            updateCount++;
            Device updated = new Device(id, edit.name() != null ? edit.name() : current.name(),
                    edit.capabilities() != null ? edit.capabilities() : current.capabilities(),
                    edit.stream() != null ? edit.stream() : current.stream(), current.state(), current.origin());
            byId.put(id, updated);
            return updated;
        }

        @Override
        public Device setState(DeviceId id, LifecycleState state, UserId actor) {
            throw new UnsupportedOperationException("not needed by this test");
        }

        @Override
        public Device delete(DeviceId id, UserId actor) {
            throw new UnsupportedOperationException("not needed by this test");
        }
    }
}
