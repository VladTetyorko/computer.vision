package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.EventHistoryEntity;
import com.drones.vision.adapter.persistence.mapper.EventHistoryMapper;
import com.drones.vision.platform.Event;
import com.drones.vision.platform.EventHistoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;

/**
 * {@link EventHistoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave B3.
 *
 * <p>{@link #record} always {@code persist}s a brand-new row, never {@code merge}s — like {@link
 * JpaAuditTrail}, recorded events are immutable historical facts with real identity ({@link
 * Event#id()}), so there is never an existing row to reconcile against.
 *
 * <h2>Retention</h2>
 * Unlike {@link JpaAuditTrail} (unbounded — an audit trail that evicts its own history defeats its
 * purpose), this table caps its **total** row count, not a per-stream/per-type count: most of what
 * lands here (device/battery/link/geofence/divergence events) has no stream to group by at all —
 * {@link Event#streamId()} is null for exactly those — so a per-key cap the way {@link
 * JpaDetectionRepository} keys by {@code streamId} would leave the ungrouped majority uncapped.
 * {@link #record} therefore prunes the oldest rows down to {@code retentionLimit} whenever the
 * table-wide count exceeds it, via one native delete query per write in the same transaction as the
 * insert, after an explicit {@code flush()} for the same reason {@link JpaDetectionRepository}/
 * {@link JpaTelemetryRepository} need one. The limit itself is supplied by the caller (wired from
 * {@code vision.events.history.retention.max-rows} in vision-app) rather than a baked-in default
 * constant here, since {@code VisionEventHistoryProperties} is already this value's one source of
 * truth — duplicating a default in this class would just be a second place for it to drift.
 */
public final class JpaEventHistory implements EventHistoryPort {

    private final JpaOperations jpa;
    private final int retentionLimit;

    /**
     * @param retentionLimit cap on rows retained across the whole table before the oldest are
     *                       pruned; must be positive
     */
    public JpaEventHistory(EntityManagerFactory entityManagerFactory, int retentionLimit) {
        this.jpa = new JpaOperations(entityManagerFactory);
        if (retentionLimit <= 0) {
            throw new IllegalArgumentException("retentionLimit must be positive: " + retentionLimit);
        }
        this.retentionLimit = retentionLimit;
    }

    @Override
    public Event record(Event event) {
        jpa.write(em -> {
            em.persist(EventHistoryMapper.toEntity(event));
            em.flush();
            pruneOldest(em);
            return null;
        });
        return event;
    }

    @Override
    public List<Event> findRecent(int limit) {
        return jpa.read(em -> {
            TypedQuery<EventHistoryEntity> query = em.createQuery(
                    "select e from EventHistoryEntity e order by e.occurredAt desc", EventHistoryEntity.class);
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(EventHistoryMapper::toDomain).toList();
    }

    @Override
    public List<Event> findSince(Instant sinceInclusive, int limit) {
        return jpa.read(em -> {
            String jpql = "select e from EventHistoryEntity e"
                    + (sinceInclusive != null ? " where e.occurredAt >= :since" : "")
                    + " order by e.occurredAt desc";
            TypedQuery<EventHistoryEntity> query = em.createQuery(jpql, EventHistoryEntity.class);
            if (sinceInclusive != null) {
                query.setParameter("since", sinceInclusive);
            }
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(EventHistoryMapper::toDomain).toList();
    }

    private void pruneOldest(EntityManager em) {
        em.createNativeQuery("""
                DELETE FROM event_history
                WHERE id NOT IN (
                    SELECT id FROM event_history ORDER BY occurred_at DESC LIMIT ?1
                )
                """)
                .setParameter(1, retentionLimit)
                .executeUpdate();
    }
}
