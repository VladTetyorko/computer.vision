package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.PairingEntity;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.model.Pairing;
import com.drones.vision.warehouse.domain.model.PairingId;
import com.drones.vision.warehouse.domain.model.RadioBind;
import com.drones.vision.warehouse.domain.model.VehicleKey;

/**
 * {@link Pairing} &harr; {@link PairingEntity} mapping, extracted from {@code
 * JpaPairingRepository} — same split {@code DeviceMapper}/{@code DiscoveryCandidateMapper} already
 * follow (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class PairingMapper {

    private PairingMapper() {
    }

    public static PairingEntity toEntity(Pairing pairing) {
        return new PairingEntity(pairing.id().value(), pairing.deviceId().value(), pairing.sysid(),
                pairing.vehicleKey().value(), pairing.hardwareUid(), pairing.radioBind().attributes(),
                pairing.createdAt(), pairing.replacedAt());
    }

    public static Pairing toDomain(PairingEntity entity) {
        return new Pairing(new PairingId(entity.id()), new DeviceId(entity.deviceId()), entity.sysid(),
                new VehicleKey(entity.vehicleKey()), entity.hardwareUid(),
                new RadioBind(entity.radioBindAttributes()), entity.createdAt(), entity.replacedAt());
    }
}
