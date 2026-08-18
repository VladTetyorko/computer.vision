package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.VehicleProfileEntity;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.kernel.DeviceId;

import java.util.UUID;

/**
 * {@link VehicleProfile} &harr; {@link VehicleProfileEntity} mapping (docs/plans/active/DRONE-ONBOARDING-PLAN.md
 * O5), extracted the same way every other mapper in this package is (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * SS3/SS7 row C).
 *
 * <p>{@link #toEntity} invents a synthetic {@code UUID} id at mapping time and takes the {@link
 * DeviceId} the port call carries separately — {@link VehicleProfile} itself has neither (see
 * {@code VehicleProfileEntity}'s javadoc).
 */
public final class VehicleProfileMapper {

    private VehicleProfileMapper() {
    }

    public static VehicleProfileEntity toEntity(DeviceId deviceId, VehicleProfile profile) {
        return new VehicleProfileEntity(UUID.randomUUID(), deviceId.value(), profile.linkKey(),
                profile.observedAt(), profile.sysid(), profile.firmware(), profile.firmwareVersion(),
                profile.vehicleKind(), profile.capabilityBitmask(), profile.capabilityFlags(), profile.messages(),
                profile.parameters(), profile.linkBytesPerSecond(), profile.complete(), profile.incompleteReason());
    }

    public static VehicleProfile toDomain(VehicleProfileEntity entity) {
        return new VehicleProfile(entity.linkKey(), entity.observedAt(), entity.sysid(), entity.firmware(),
                entity.firmwareVersion(), entity.vehicleKind(), entity.capabilityBitmask(), entity.capabilityFlags(),
                entity.messages(), entity.parameters(), entity.linkBytesPerSecond(), entity.complete(),
                entity.incompleteReason());
    }
}
