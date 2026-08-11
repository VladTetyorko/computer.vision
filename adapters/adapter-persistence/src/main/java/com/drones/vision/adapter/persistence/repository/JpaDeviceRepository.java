package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DeviceEntity;
import com.drones.vision.adapter.persistence.mapper.DeviceMapper;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.kernel.DeviceId;
import com.drones.vision.warehouse.domain.port.DeviceRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link DeviceRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} is an upsert (merge-by-id); {@link #deleteById} is a real hard delete of the
 * row (idempotent — a missing id is simply a no-op), matching {@code InMemoryDeviceRepository}'s
 * {@code Map#remove} semantics exactly. Soft-delete (a device's {@code LifecycleState}) is just a
 * field value here, round-tripped like any other column — nothing in this class treats
 * {@code DELETED} specially, exactly like the in-memory reference implementation.
 */
public final class JpaDeviceRepository implements DeviceRepositoryPort {

    private final JpaOperations jpa;

    public JpaDeviceRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Device save(Device device) {
        DeviceEntity saved = jpa.write(em -> em.merge(DeviceMapper.toEntity(device)));
        return DeviceMapper.toDomain(saved);
    }

    @Override
    public Optional<Device> findById(DeviceId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(DeviceEntity.class, id.value())))
                .map(DeviceMapper::toDomain);
    }

    @Override
    public List<Device> findAll() {
        return jpa.read(em -> em.createQuery("select d from DeviceEntity d", DeviceEntity.class)
                        .getResultList())
                .stream()
                .map(DeviceMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(DeviceId id) {
        jpa.write(em -> {
            DeviceEntity existing = em.find(DeviceEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
