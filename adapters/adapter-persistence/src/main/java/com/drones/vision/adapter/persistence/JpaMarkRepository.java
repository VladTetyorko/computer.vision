package com.drones.vision.adapter.persistence;

import com.drones.vision.adapter.persistence.entity.MarkEntity;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.Mark;
import com.drones.vision.domain.model.MarkId;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;
import com.drones.vision.domain.port.out.MarkRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link MarkRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/TACTICAL-MARKS-PLAN.md §3.
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
        MarkEntity saved = jpa.write(em -> em.merge(toEntity(mark)));
        return toDomain(saved);
    }

    @Override
    public Optional<Mark> findById(MarkId id) {
        return jpa.read(em -> Optional.ofNullable(em.find(MarkEntity.class, id.value())))
                .map(JpaMarkRepository::toDomain);
    }

    @Override
    public List<Mark> findAll() {
        return jpa.read(em -> em.createQuery("select m from MarkEntity m", MarkEntity.class)
                        .getResultList())
                .stream()
                .map(JpaMarkRepository::toDomain)
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

    private static MarkEntity toEntity(Mark mark) {
        GeoPosition position = mark.position();
        return new MarkEntity(mark.id().value(), mark.kind(), mark.label(), mark.note(),
                position.latitude(), position.longitude(), position.altitudeMeters(),
                mark.ownership().ownerId().value(), mark.ownership().groupId().value(),
                mark.createdAt(), mark.status(), mark.source());
    }

    private static Mark toDomain(MarkEntity entity) {
        GeoPosition position = new GeoPosition(entity.latitude(), entity.longitude(), entity.altitudeMeters());
        Ownership ownership = new Ownership(new UserId(entity.ownerId()), new GroupId(entity.groupId()));
        return new Mark(new MarkId(entity.id()), position, entity.kind(), entity.label(), entity.note(),
                ownership, entity.createdAt(), entity.status(), entity.source());
    }
}
