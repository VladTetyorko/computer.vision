package com.drones.vision.warehouse.application.directory;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.Capability;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.kernel.UserId;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAssetDirectoryServiceTest {

    private AssetRepositoryPort assetRepository;
    private DeviceRepositoryPort deviceRepository;
    private DefaultAssetDirectoryService service;

    @BeforeEach
    void setUp() {
        assetRepository = mock(AssetRepositoryPort.class);
        deviceRepository = mock(DeviceRepositoryPort.class);
        service = new DefaultAssetDirectoryService(assetRepository, deviceRepository);
    }

    @Test
    void findByDeviceDelegatesToAssetRepository() {
        DeviceId deviceId = DeviceId.random();
        Asset asset = new Asset(AssetId.random(), "drone", new CategoryId("drone"),
                new Ownership(UserId.random(), GroupId.random()), Set.of(deviceId), Map.of());
        when(assetRepository.findByDeviceId(deviceId)).thenReturn(Optional.of(asset));

        assertEquals(Optional.of(asset), service.findByDevice(deviceId));
    }

    @Test
    void findByDeviceIsEmptyForAnUnownedDevice() {
        DeviceId deviceId = DeviceId.random();
        when(assetRepository.findByDeviceId(deviceId)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.findByDevice(deviceId));
    }

    @Test
    void findDeviceDelegatesToDeviceRepository() {
        Device device = new Device(DeviceId.random(), "tel-1", Set.of(Capability.TELEMETRY),
                new StreamDescriptor("sim", URI.create("sim://tel-1"), Map.of()));
        when(deviceRepository.findById(device.id())).thenReturn(Optional.of(device));

        assertEquals(Optional.of(device), service.findDevice(device.id()));
    }

    @Test
    void findDeviceIsEmptyForAnUnknownDevice() {
        DeviceId deviceId = DeviceId.random();
        when(deviceRepository.findById(deviceId)).thenReturn(Optional.empty());

        assertEquals(Optional.empty(), service.findDevice(deviceId));
    }

    @Test
    void rejectsNullDeviceId() {
        assertThrows(NullPointerException.class, () -> service.findByDevice(null));
        assertThrows(NullPointerException.class, () -> service.findDevice(null));
    }

    @Test
    void constructorRejectsNullCollaborators() {
        assertThrows(NullPointerException.class, () -> new DefaultAssetDirectoryService(null, deviceRepository));
        assertThrows(NullPointerException.class, () -> new DefaultAssetDirectoryService(assetRepository, null));
    }
}
