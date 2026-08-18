package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.FeatureRequirementEntity;
import com.drones.vision.adapter.persistence.mapper.FeatureRequirementMapper;
import com.drones.vision.flight.domain.model.FeatureRequirement;
import com.drones.vision.flight.domain.port.FeatureRequirementRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;

/**
 * {@link FeatureRequirementRepositoryPort} backed by Postgres via plain JPA (see {@link
 * JpaOperations}) — docs/plans/active/DRONE-ONBOARDING-PLAN.md O5.
 *
 * <p>Read-only: the port has no {@code save} method — every row is seed data written by Flyway
 * ({@code V18__feature_requirements.sql}, D6: "a new requirement is a migration, never a
 * five-layer edit"), never by application code.
 */
public final class JpaFeatureRequirementRepository implements FeatureRequirementRepositoryPort {

    private final JpaOperations jpa;

    public JpaFeatureRequirementRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public List<FeatureRequirement> findByFirmware(String firmware) {
        return jpa.read(em -> em.createQuery(
                        "select r from FeatureRequirementEntity r where r.firmware = :firmware",
                        FeatureRequirementEntity.class)
                        .setParameter("firmware", firmware)
                        .getResultList())
                .stream()
                .map(FeatureRequirementMapper::toDomain)
                .toList();
    }

    @Override
    public List<FeatureRequirement> findAll() {
        return jpa.read(em -> em.createQuery("select r from FeatureRequirementEntity r",
                        FeatureRequirementEntity.class)
                        .getResultList())
                .stream()
                .map(FeatureRequirementMapper::toDomain)
                .toList();
    }
}
