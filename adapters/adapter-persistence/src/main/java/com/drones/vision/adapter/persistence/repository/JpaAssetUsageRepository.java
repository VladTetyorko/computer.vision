package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.adapter.persistence.mapper.AssetUsageMapper;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.AssetUsageRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link AssetUsageRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryAssetUsageRepository}'s
 * {@code Map#put} semantics exactly. {@link #findRecentByAsset}/{@link #findOpenByAsset} run real
 * indexed queries (on {@code asset_usages.asset_id}, see {@code V3__history.sql}) rather than the
 * in-memory reference's linear scan, but the observable contract — newest-first, bounded to
 * {@code limit}; at most one open usage per asset — is identical. No retention pruning here (unlike
 * {@link com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository}/{@link
 * com.drones.vision.adapter.persistence.repository.JpaDetectionRepository}): a usage row is
 * written once per start/stop and a handful of times in between (position/sample-count updates),
 * not once per incoming sample, so it is not the "append-heavy" table docs/MVP2-PLAN.md P-b's
 * retention guard targets.
 */
public final class JpaAssetUsageRepository implements AssetUsageRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssetUsageRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public AssetUsage save(AssetUsage usage) {
        AssetUsageEntity saved = jpa.write(em -> em.merge(AssetUsageMapper.toEntity(usage)));
        return AssetUsageMapper.toDomain(saved);
    }

    @Override
    public Optional<AssetUsage> findById(UsageId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(AssetUsageEntity.class, id.value())))
                .map(AssetUsageMapper::toDomain);
    }

    @Override
    public List<AssetUsage> findRecentByAsset(AssetId assetId, int limit) {
        return jpa.read(em -> em.createQuery(
                        "select u from AssetUsageEntity u where u.assetId = :assetId order by u.startedAt desc",
                        AssetUsageEntity.class)
                        .setParameter("assetId", assetId.value())
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(AssetUsageMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<AssetUsage> findOpenByAsset(AssetId assetId) {
        List<AssetUsageEntity> open = jpa.read(em -> em.createQuery(
                        "select u from AssetUsageEntity u where u.assetId = :assetId and u.endedAt is null",
                        AssetUsageEntity.class)
                .setParameter("assetId", assetId.value())
                .setMaxResults(1)
                .getResultList());
        return open.stream().findFirst().map(AssetUsageMapper::toDomain);
    }
}
