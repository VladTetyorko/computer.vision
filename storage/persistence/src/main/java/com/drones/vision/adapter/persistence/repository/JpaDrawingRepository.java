package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.MapDrawingEntity;
import com.drones.vision.adapter.persistence.mapper.DrawingMapper;
import com.drones.vision.map.domain.model.Drawing;
import com.drones.vision.map.domain.model.DrawingId;
import com.drones.vision.map.domain.port.DrawingRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Postgres-backed {@link DrawingRepositoryPort} (docs/plans/done/MAP-REWORK-PLAN.md §4.4, {@code
 * V12__map_layers.sql}) — {@code save} is merge-by-id (upsert), {@code deleteById} a real hard
 * delete, idempotent: the same shape {@code JpaMarkRepository} has, for the same reason (a drawing
 * has real identity and mutates in place as its geometry is edited).
 */
public final class JpaDrawingRepository implements DrawingRepositoryPort {

    private final JpaOperations jpa;

    public JpaDrawingRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Drawing save(Drawing drawing) {
        MapDrawingEntity saved = jpa.write(em -> em.merge(DrawingMapper.toEntity(drawing)));
        return DrawingMapper.toDomain(saved);
    }

    @Override
    public Optional<Drawing> findById(DrawingId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(MapDrawingEntity.class, id.value())))
                .map(DrawingMapper::toDomain);
    }

    @Override
    public List<Drawing> findAll() {
        return jpa.read(em -> em.createQuery("select d from MapDrawingEntity d", MapDrawingEntity.class)
                        .getResultList())
                .stream()
                .map(DrawingMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(DrawingId id) {
        jpa.write(em -> {
            MapDrawingEntity existing = em.find(MapDrawingEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
