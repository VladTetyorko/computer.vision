package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.DeviceEntity;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.StreamDescriptor;

import java.net.URI;

/**
 * {@link Device} &harr; {@link DeviceEntity} mapping, extracted from {@code JpaDeviceRepository}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class DeviceMapper {

    private DeviceMapper() {
    }

    public static DeviceEntity toEntity(Device device) {
        StreamDescriptor stream = device.stream();
        return new DeviceEntity(device.id().value(), device.name(), device.capabilities(),
                stream.protocol(), stream.uri().toString(), stream.options(), device.state());
    }

    public static Device toDomain(DeviceEntity entity) {
        StreamDescriptor stream = new StreamDescriptor(entity.streamProtocol(), URI.create(entity.streamUri()),
                entity.streamOptions());
        return new Device(new DeviceId(entity.id()), entity.name(), entity.capabilities(), stream, entity.state());
    }
}
