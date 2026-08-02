package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.adapter.persistence.mapper.AssetMapper;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link AssetRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} is an upsert (merge-by-id); {@link #deleteById} is a real hard delete of the
 * row (idempotent), matching {@code InMemoryAssetRepository}'s {@code Map#remove} semantics
 * exactly. {@link #findByDeviceId} runs a real query (an {@code AssetEntity}/{@code deviceIds}
 * element-collection join) rather than {@code InMemoryAssetRepository}'s in-JVM linear scan, but
 * the observable contract — "a device belongs to &le;1 asset", empty {@link Optional} when none
 * do — is identical.
 */
public final class JpaAssetRepository implements AssetRepositoryPort {

    private final JpaOperations jpa;

    public JpaAssetRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Asset save(Asset asset) {
        AssetEntity saved = jpa.write(em -> em.merge(AssetMapper.toEntity(asset)));
        return AssetMapper.toDomain(saved);
    }

    @Override
    public Optional<Asset> findById(AssetId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(AssetEntity.class, id.value())))
                .map(AssetMapper::toDomain);
    }

    @Override
    public List<Asset> findAll() {
        return jpa.read(em -> em.createQuery("select a from AssetEntity a", AssetEntity.class)
                        .getResultList())
                .stream()
                .map(AssetMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<Asset> findByDeviceId(DeviceId deviceId) {
        List<AssetEntity> matches = jpa.read(em -> em.createQuery(
                        "select a from AssetEntity a join a.deviceIds d where d = :deviceId", AssetEntity.class)
                .setParameter("deviceId", deviceId.value())
                .setMaxResults(1)
                .getResultList());
        return matches.stream().findFirst().map(AssetMapper::toDomain);
    }

    @Override
    public void deleteById(AssetId id) {
        jpa.write(em -> {
            AssetEntity existing = em.find(AssetEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
