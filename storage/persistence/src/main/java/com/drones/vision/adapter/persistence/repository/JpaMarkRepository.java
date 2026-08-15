package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.adapter.persistence.mapper.MarkMapper;
import com.drones.vision.map.domain.model.Mark;
import com.drones.vision.map.domain.model.MarkId;
import com.drones.vision.map.domain.port.MarkRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link MarkRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/TACTICAL-MARKS-PLAN.md §3.
 *
 * <p>{@link #save} is an upsert (merge-by-id), matching {@code InMemoryMarkRepository}'s
 * ({@code vision-app} devsupport) {@code Map#put} semantics exactly. {@link #deleteById} is a
 * real hard delete, idempotent (a missing id is a no-op) — same shape as {@link
 * JpaGeofenceRepository}, {@code Mark}'s own template.
 */
public final class JpaMarkRepository implements MarkRepositoryPort {

    private final JpaOperations jpa;

    public JpaMarkRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Mark save(Mark mark) {
        MarkEntity saved = jpa.write(em -> em.merge(MarkMapper.toEntity(mark)));
        return MarkMapper.toDomain(saved);
    }

    @Override
    public Optional<Mark> findById(MarkId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(MarkEntity.class, id.value())))
                .map(MarkMapper::toDomain);
    }

    @Override
    public List<Mark> findAll() {
        return jpa.read(em -> em.createQuery("select m from MarkEntity m", MarkEntity.class)
                        .getResultList())
                .stream()
                .map(MarkMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteById(MarkId id) {
        jpa.write(em -> {
            MarkEntity existing = em.find(MarkEntity.class, id.value());
            if (existing != null) {
                em.remove(existing);
            }
            return null;
        });
    }
}
