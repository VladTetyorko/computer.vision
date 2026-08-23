package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.AuditEntryEntity;
import com.drones.vision.adapter.persistence.mapper.AuditEntryMapper;
import com.drones.vision.kernel.UserId;
import com.drones.vision.platform.AuditEntry;
import com.drones.vision.platform.AuditTargetType;
import com.drones.vision.platform.AuditTrailPort;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;

/**
 * {@link AuditTrailPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/done/POSTGRES-ONLY-CONTEXT.md W3.
 *
 * <p>{@link #record} always {@code persist}s a brand-new row, never {@code merge}s — per the
 * port's own contract, entries are immutable historical facts that are never updated or deleted,
 * so there is never an existing row to reconcile against (same "append-only, id never
 * repeated" shape {@link JpaDetectionRepository}/{@link JpaTelemetryRepository} use for their own
 * immutable history rows, except here the id is the domain's own {@code AuditId} rather than a
 * synthetic one — an entry has real identity).
 *
 * <p>All three read methods share one JPQL shape (an optional {@code WHERE}, then {@code order by
 * occurredAt desc} bounded by {@code limit}) — no retention pruning: an audit trail that evicts its
 * own oldest rows is not durable in the way this port exists to guarantee (see the port's own
 * javadoc). Unbounded growth is an accepted tradeoff at this platform's scale, the same posture
 * {@link com.drones.vision.adapter.persistence.repository.JpaAssetUsageRepository} already takes
 * for a low-write-volume table.
 */
public final class JpaAuditTrail implements AuditTrailPort {

    private final JpaOperations jpa;

    public JpaAuditTrail(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public AuditEntry record(AuditEntry entry) {
        jpa.write(em -> {
            em.persist(AuditEntryMapper.toEntity(entry));
            return null;
        });
        return entry;
    }

    @Override
    public List<AuditEntry> findRecent(int limit) {
        return jpa.read(em -> {
            TypedQuery<AuditEntryEntity> query = em.createQuery(
                    "select a from AuditEntryEntity a order by a.occurredAt desc", AuditEntryEntity.class);
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(AuditEntryMapper::toDomain).toList();
    }

    @Override
    public List<AuditEntry> findByTarget(AuditTargetType targetType, String targetId, int limit) {
        return jpa.read(em -> {
            TypedQuery<AuditEntryEntity> query = em.createQuery(
                    "select a from AuditEntryEntity a where a.targetType = :targetType and a.targetId = :targetId "
                            + "order by a.occurredAt desc",
                    AuditEntryEntity.class);
            query.setParameter("targetType", targetType);
            query.setParameter("targetId", targetId);
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(AuditEntryMapper::toDomain).toList();
    }

    @Override
    public List<AuditEntry> findByActor(UserId actor, int limit) {
        return jpa.read(em -> {
            TypedQuery<AuditEntryEntity> query = em.createQuery(
                    "select a from AuditEntryEntity a where a.actorId = :actorId order by a.occurredAt desc",
                    AuditEntryEntity.class);
            query.setParameter("actorId", actor.value());
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(AuditEntryMapper::toDomain).toList();
    }
}
