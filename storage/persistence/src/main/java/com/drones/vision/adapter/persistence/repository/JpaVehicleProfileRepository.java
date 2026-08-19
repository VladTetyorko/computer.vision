package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.VehicleProfileEntity;
import com.drones.vision.adapter.persistence.mapper.VehicleProfileMapper;
import com.drones.vision.flight.domain.model.FlightPhase;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.kernel.UsageId;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link VehicleProfileRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/DRONE-ONBOARDING-PLAN.md O5/O11.
 *
 * <p>Both {@code save} overloads always {@code persist} a brand-new row — the port's own contract is
 * append-only, "never overwrites" (O3 MODULE.md), and {@link VehicleProfile} carries no id to merge
 * by anyway (see {@code VehicleProfileEntity}'s javadoc). {@link #findLatest} is "the newest row for
 * this device", {@code order by observedAt desc} + {@code setMaxResults(1)}, same shape as {@code
 * JpaAssetRepository}/{@code JpaAssetUsageRepository}'s own single-row-latest queries. {@link
 * #findByUsageAndPhase} (O11) is the same "newest row" shape, filtered on {@code usage_id}/{@code
 * phase} instead of {@code device_id} — {@code idx_vehicle_profiles_usage_phase} (V20) backs it.
 */
public final class JpaVehicleProfileRepository implements VehicleProfileRepositoryPort {

    private final JpaOperations jpa;

    public JpaVehicleProfileRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void save(DeviceId deviceId, VehicleProfile profile) {
        jpa.write(em -> {
            em.persist(VehicleProfileMapper.toEntity(deviceId, profile));
            return null;
        });
    }

    @Override
    public void save(DeviceId deviceId, UsageId usageId, FlightPhase phase, VehicleProfile profile) {
        jpa.write(em -> {
            em.persist(VehicleProfileMapper.toEntity(deviceId, usageId, phase, profile));
            return null;
        });
    }

    @Override
    public Optional<VehicleProfile> findLatest(DeviceId deviceId) {
        List<VehicleProfileEntity> latest = jpa.read(em -> em.createQuery(
                        "select p from VehicleProfileEntity p where p.deviceId = :deviceId "
                                + "order by p.observedAt desc",
                        VehicleProfileEntity.class)
                .setParameter("deviceId", deviceId.value())
                .setMaxResults(1)
                .getResultList());
        return latest.stream().findFirst().map(VehicleProfileMapper::toDomain);
    }

    @Override
    public Optional<VehicleProfile> findByUsageAndPhase(UsageId usageId, FlightPhase phase) {
        List<VehicleProfileEntity> latest = jpa.read(em -> em.createQuery(
                        "select p from VehicleProfileEntity p where p.usageId = :usageId and p.phase = :phase "
                                + "order by p.observedAt desc",
                        VehicleProfileEntity.class)
                .setParameter("usageId", usageId.value())
                .setParameter("phase", phase)
                .setMaxResults(1)
                .getResultList());
        return latest.stream().findFirst().map(VehicleProfileMapper::toDomain);
    }
}
