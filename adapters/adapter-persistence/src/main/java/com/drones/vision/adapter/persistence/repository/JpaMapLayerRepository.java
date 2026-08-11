package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.MapLayerEntity;
import com.drones.vision.adapter.persistence.mapper.MapLayerMapper;
import com.drones.vision.map.domain.model.LayerId;
import com.drones.vision.map.domain.model.MapLayer;
import com.drones.vision.map.domain.port.MapLayerRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link MapLayerRepositoryPort} (docs/plans/done/MAP-REWORK-PLAN.md §4.4, {@code
 * V12__map_layers.sql}) — {@code save} is merge-by-id (upsert), {@code deleteById} a real hard
 * delete, idempotent: the same shape {@code JpaGeofenceRepository}/{@code JpaMarkRepository} already
 * have.
 *
 * <p>A layer's grant list rides along with the aggregate through the {@code map_layer_grants}
 * element collection, so {@code merge} replaces it wholesale — exactly the semantics {@code
 * MapLayerService#setGrants} defines ("wholesale, not a delta") and exactly what {@code
 * InMemoryMapLayerRepository}'s single-{@code put} does.
 *
 * <p>Deleting a layer here does <strong>not</strong> cascade to its marks/drawings: that cascade is
 * application-level ({@code DefaultMapLayerService#delete}, which must publish one {@code MapEvent}
 * per removed row). The {@code ON DELETE CASCADE} in the schema applies only to the grants child
 * table.
 */
public final class JpaMapLayerRepository implements MapLayerRepositoryPort {

    private final JpaOperations jpa;

    public JpaMapLayerRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public MapLayer save(MapLayer layer) {
        MapLayerEntity saved = jpa.write(em -> em.merge(MapLayerMapper.toEntity(layer)));
        return MapLayerMapper.toDomain(saved);
    }

    @Override
    public Optional<MapLayer> findById(LayerId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(MapLayerEntity.class, id.value())))
                .map(MapLayerMapper::toDomain);
    }

    @Override
    public List<MapLayer> findAll() {
        return jpa.read(em -> em.createQuery("select l from MapLayerEntity l", MapLayerEntity.class)
                        .getResultList())
                .stream()
                .map(MapLayerMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(LayerId id) {
        jpa.write(em -> {
            MapLayerEntity existing = em.find(MapLayerEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
