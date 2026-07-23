package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.AssetUsageEntity;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetUsage;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.StreamId;
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
 * {@link JpaTelemetryRepository}/{@link JpaDetectionRepository}): a usage row is written once per
 * start/stop and a handful of times in between (position/sample-count updates), not once per
 * incoming sample, so it is not the "append-heavy" table docs/MVP2-PLAN.md P-b's retention guard
 * targets.
 */
public final class JpaAssetUsageRepository implements AssetUsageRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssetUsageRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public AssetUsage save(AssetUsage usage) {
        AssetUsageEntity saved = jpa.write(em -> em.merge(toEntity(usage)));
        return toDomain(saved);
    }

    @Override
    public Optional<AssetUsage> findById(UsageId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(AssetUsageEntity.class, id.value())))
                .map(JpaAssetUsageRepository::toDomain);
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
                .map(JpaAssetUsageRepository::toDomain)
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
        return open.stream().findFirst().map(JpaAssetUsageRepository::toDomain);
    }

    private static AssetUsageEntity toEntity(AssetUsage usage) {
        GeoPosition start = usage.startPosition();
        GeoPosition last = usage.lastPosition();
        StreamId streamId = usage.streamId();
        return new AssetUsageEntity(usage.id().value(), usage.assetId().value(), usage.startedAt(), usage.endedAt(),
                start == null ? null : start.latitude(), start == null ? null : start.longitude(),
                start == null ? null : start.altitudeMeters(),
                last == null ? null : last.latitude(), last == null ? null : last.longitude(),
                last == null ? null : last.altitudeMeters(),
                usage.sampleCount(), streamId == null ? null : streamId.value());
    }

    private static AssetUsage toDomain(AssetUsageEntity entity) {
        GeoPosition start = entity.startLatitude() == null ? null
                : new GeoPosition(entity.startLatitude(), entity.startLongitude(), entity.startAltitudeMeters());
        GeoPosition last = entity.lastLatitude() == null ? null
                : new GeoPosition(entity.lastLatitude(), entity.lastLongitude(), entity.lastAltitudeMeters());
        StreamId streamId = entity.streamId() == null ? null : new StreamId(entity.streamId());
        return new AssetUsage(new UsageId(entity.id()), new AssetId(entity.assetId()), entity.startedAt(),
                entity.endedAt(), start, last, entity.sampleCount(), streamId);
    }
}
