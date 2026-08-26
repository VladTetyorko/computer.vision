package com.drones.vision.warehouse.application.directory;

import com.drones.vision.warehouse.domain.model.Asset;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.warehouse.domain.port.AssetRepositoryPort;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;

import java.util.Objects;
import java.util.Optional;

/**
 * {@link AssetDirectoryService} default implementation: a direct pass-through over {@link
 * AssetRepositoryPort#findByDeviceId} and {@link DeviceRepositoryPort#findById} — see the
 * interface javadoc for why this reads the two repository ports directly instead of delegating to
 * {@code AssetService}/{@code DeviceService}.
 */
public final class DefaultAssetDirectoryService implements AssetDirectoryService {

    private final AssetRepositoryPort assetRepository;
    private final DeviceRepositoryPort deviceRepository;

    public DefaultAssetDirectoryService(AssetRepositoryPort assetRepository, DeviceRepositoryPort deviceRepository) {
        this.assetRepository = Objects.requireNonNull(assetRepository, "assetRepository must not be null");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository must not be null");
    }

    @Override
    public Optional<Asset> findByDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return assetRepository.findByDeviceId(deviceId);
    }

    @Override
    public Optional<Device> findDevice(DeviceId deviceId) {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        return deviceRepository.findById(deviceId);
    }
}
