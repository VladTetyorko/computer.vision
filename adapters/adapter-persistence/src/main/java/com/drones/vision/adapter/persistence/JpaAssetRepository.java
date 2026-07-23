package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.AssetEntity;
import com.drones.vision.domain.model.Asset;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.CategoryId;
import com.drones.vision.domain.model.DeviceId;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.AssetRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
        AssetEntity saved = jpa.write(em -> em.merge(toEntity(asset)));
        return toDomain(saved);
    }

    @Override
    public Optional<Asset> findById(AssetId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(AssetEntity.class, id.value())))
                .map(JpaAssetRepository::toDomain);
    }

    @Override
    public List<Asset> findAll() {
        return jpa.read(em -> em.createQuery("select a from AssetEntity a", AssetEntity.class)
                        .getResultList())
                .stream()
                .map(JpaAssetRepository::toDomain)
                .toList();
    }

    @Override
    public Optional<Asset> findByDeviceId(DeviceId deviceId) {
        List<AssetEntity> matches = jpa.read(em -> em.createQuery(
                        "select a from AssetEntity a join a.deviceIds d where d = :deviceId", AssetEntity.class)
                .setParameter("deviceId", deviceId.value())
                .setMaxResults(1)
                .getResultList());
        return matches.stream().findFirst().map(JpaAssetRepository::toDomain);
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

    private static AssetEntity toEntity(Asset asset) {
        Set<UUID> deviceIds = new LinkedHashSet<>();
        asset.devices().forEach(deviceId -> deviceIds.add(deviceId.value()));
        return new AssetEntity(asset.id().value(), asset.displayName(), asset.category().slug(),
                asset.ownership().ownerId().value(), asset.ownership().groupId().value(),
                deviceIds, asset.attributes(), asset.state());
    }

    private static Asset toDomain(AssetEntity entity) {
        Set<DeviceId> deviceIds = new LinkedHashSet<>();
        entity.deviceIds().forEach(value -> deviceIds.add(new DeviceId(value)));
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        return new Asset(new AssetId(entity.id()), entity.displayName(), new CategoryId(entity.categoryId()),
                ownership, deviceIds, entity.attributes(), entity.state());
    }
}
