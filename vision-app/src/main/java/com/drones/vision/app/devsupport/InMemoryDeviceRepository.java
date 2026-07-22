package com.drones.vision.app.devsupport;

import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.port.out.DeviceRepositoryPort;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link DeviceRepositoryPort}: dev/Phase-0 fallback with no
 * durability across restarts.
 *
 * <p>Replaced by {@code adapter-persistence} (JPA/Postgres), planned for
 * Phase 2.
 */
public final class InMemoryDeviceRepository implements DeviceRepositoryPort {

    private final Map<DeviceId, Device> devices = new ConcurrentHashMap<>();

    @Override
    public Device save(Device device) {
        devices.put(device.id(), device);
        return device;
    }

    @Override
    public Optional<Device> findById(DeviceId id) {
        return Optional.ofNullable(devices.get(id));
    }

    @Override
    public List<Device> findAll() {
        return List.copyOf(devices.values());
    }

    @Override
    public void deleteById(DeviceId id) {
        devices.remove(id);
    }
}
