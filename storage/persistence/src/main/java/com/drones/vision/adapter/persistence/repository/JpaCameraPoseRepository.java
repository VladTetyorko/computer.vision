package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.CameraPoseEntity;
import com.drones.vision.adapter.persistence.mapper.CameraPoseMapper;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.port.CameraPoseRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link CameraPoseRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/FIXED-CAMERA-GEO-PLAN.md decision D4.
 *
 * <p>{@link #save} is an upsert (merge-by-{@code assetId}), matching the port's own "one row per
 * asset" contract exactly — same shape as {@link JpaAssetImageRepository}, whose owning id also
 * doubles as the primary key. {@link #deleteByAssetId} is a real hard delete, idempotent — same
 * shape as {@link JpaAssetImageRepository#deleteByAssetId}.
 */
public final class JpaCameraPoseRepository implements CameraPoseRepositoryPort {

    private final JpaOperations jpa;

    public JpaCameraPoseRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public CameraPose save(CameraPose pose) {
        CameraPoseEntity saved = jpa.write(em -> em.merge(CameraPoseMapper.toEntity(pose)));
        return CameraPoseMapper.toDomain(saved);
    }

    @Override
    public Optional<CameraPose> findByAssetId(AssetId assetId) {
        return jpa.read(em -> Optional.ofNullable(em.find(CameraPoseEntity.class, assetId.value())))
                .map(CameraPoseMapper::toDomain);
    }

    @Override
    public List<CameraPose> findAll() {
        return jpa.read(em -> em.createQuery("select p from CameraPoseEntity p", CameraPoseEntity.class)
                        .getResultList())
                .stream()
                .map(CameraPoseMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByAssetId(AssetId assetId) {
        jpa.write(em -> {
            CameraPoseEntity existing = em.find(CameraPoseEntity.class, assetId.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
