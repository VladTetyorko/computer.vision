package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.VehicleProfileEntity;
import com.drones.vision.adapter.persistence.mapper.VehicleProfileMapper;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleProfileRepositoryPort;
import com.drones.vision.kernel.DeviceId;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link VehicleProfileRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/DRONE-ONBOARDING-PLAN.md O5.
 *
 * <p>{@link #save} always {@code persist}s a brand-new row — the port's own contract is append-only,
 * "never overwrites" (O3 MODULE.md), and {@link VehicleProfile} carries no id to merge by anyway
 * (see {@code VehicleProfileEntity}'s javadoc). {@link #findLatest} is "the newest row for this
 * device", {@code order by observedAt desc} + {@code setMaxResults(1)}, same shape as {@code
 * JpaAssetRepository}/{@code JpaAssetUsageRepository}'s own single-row-latest queries.
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
}
