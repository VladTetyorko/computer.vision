package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AssetImageEntity;
import com.drones.vision.adapter.persistence.mapper.AssetImageMapper;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetImage;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.Optional;

/**
 * {@link AssetImageRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/UX-REWORK-PLAN.md §U-d item 3.
 *
 * <p>{@link #save} is an upsert (merge-by-{@code assetId}), matching {@code
 * InMemoryAssetImageRepository}'s {@code Map#put} exactly. {@link #existsByAssetId} runs a {@code
 * count(a)} JPQL query rather than {@link #findByAssetId} — Hibernate translates that into a plain
 * SQL {@code COUNT(*)}, never fetching the {@code bytea} column, so a fleet/asset list populating
 * {@code hasImage} for many rows never pays for loading image bytes it doesn't need.
 */
public final class JpaAssetImageRepository implements AssetImageRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssetImageRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public void save(AssetId assetId, AssetImage image) {
        jpa.write(em -> em.merge(AssetImageMapper.toEntity(assetId, image)));
    }

    @Override
    public Optional<AssetImage> findByAssetId(AssetId assetId) {
        return jpa.read(em -> Optional.ofNullable(em.find(AssetImageEntity.class, assetId.value())))
                .map(AssetImageMapper::toDomain);
    }

    @Override
    public boolean existsByAssetId(AssetId assetId) {
        Long count = jpa.read(em -> em.createQuery(
                        "select count(a) from AssetImageEntity a where a.assetId = :assetId", Long.class)
                .setParameter("assetId", assetId.value())
                .getSingleResult());
        return count > 0;
    }

    @Override
    public void deleteByAssetId(AssetId assetId) {
        jpa.write(em -> {
            AssetImageEntity existing = em.find(AssetImageEntity.class, assetId.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
