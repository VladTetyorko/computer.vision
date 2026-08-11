package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.GeofenceZoneEntity;
import com.drones.vision.adapter.persistence.mapper.GeofenceZoneMapper;
import com.drones.vision.domain.model.GeofenceZone;
import com.drones.vision.domain.model.ZoneId;
import com.drones.vision.domain.port.out.GeofenceRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link GeofenceRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/OPS-CORE-PLAN.md §G.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryGeofenceRepository}'s
 * ({@code vision-app} devsupport) {@code Map#put} semantics exactly. {@link #deleteById} is a real
 * hard delete, idempotent (a missing id is a no-op) — zones have no soft-delete concept of their
 * own; a disabled zone is just a row with {@code enabled=false}, not a lifecycle state, so there
 * is nothing here to mirror {@code Device}/{@code Asset}'s soft-delete convention for.
 */
public final class JpaGeofenceRepository implements GeofenceRepositoryPort {

    private final JpaOperations jpa;

    public JpaGeofenceRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public GeofenceZone save(GeofenceZone zone) {
        GeofenceZoneEntity saved = jpa.write(em -> em.merge(GeofenceZoneMapper.toEntity(zone)));
        return GeofenceZoneMapper.toDomain(saved);
    }

    @Override
    public Optional<GeofenceZone> findById(ZoneId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(GeofenceZoneEntity.class, id.value())))
                .map(GeofenceZoneMapper::toDomain);
    }

    @Override
    public List<GeofenceZone> findAll() {
        return jpa.read(em -> em.createQuery("select z from GeofenceZoneEntity z", GeofenceZoneEntity.class)
                        .getResultList())
                .stream()
                .map(GeofenceZoneMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(ZoneId id) {
        jpa.write(em -> {
            GeofenceZoneEntity existing = em.find(GeofenceZoneEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
