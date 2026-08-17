package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DetectionEventEntity;
import com.drones.vision.adapter.persistence.mapper.DetectionEventMapper;
import com.drones.vision.kernel.StreamId;
import com.drones.vision.perception.domain.model.DetectionEvent;
import com.drones.vision.perception.domain.port.DetectionEventRepositoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link DetectionEventRepositoryPort} backed by Postgres via plain JPA (see {@link
 * JpaOperations}) — docs/plans/active/POSTGRES-ONLY-CONTEXT.md W3.
 *
 * <p>{@link #save} is a genuine upsert (merge-by-id), matching {@code
 * InMemoryDetectionEventRepository}'s remove-then-re-add-by-id semantics exactly: a {@link
 * DetectionEvent} mutates over its own open lifetime ({@code lastSeen}/{@code peakConfidence}
 * advancing, then a final close), so an id seen before is replaced in place rather than appended
 * as a new row — unlike {@link JpaDetectionRepository}/{@link JpaTelemetryRepository}, whose rows
 * are genuinely immutable and therefore always {@code persist}, never {@code merge}.
 *
 * <h2>Retention</h2>
 * The in-memory reference implementation caps each stream's ring at 500 <em>distinct</em> events —
 * a devsupport-scale number that would be a poor fit for a durable table meant to survive
 * restarts. Instead of reproducing that cap verbatim, this class follows the retention pattern
 * this module's other append-heavy history repositories ({@link JpaDetectionRepository}, keyed by
 * the same {@code streamId} grouping column) already established: prune the stream's oldest rows
 * down to {@link #retentionLimitPerStream} on every write, inside the same transaction as the
 * upsert, via one native {@code DELETE ... WHERE ... NOT IN (SELECT ... ORDER BY last_seen DESC
 * LIMIT n)} query. The explicit {@code flush()} between the {@code merge} and the native delete is
 * required for the same reason {@link JpaDetectionRepository}/{@link JpaTelemetryRepository} need
 * one — Hibernate does not know a hand-written native query touches {@code detection_events}, so
 * without it the prune query would run against the connection's pre-write view of the table.
 */
public final class JpaDetectionEventRepository implements DetectionEventRepositoryPort {

    /**
     * Default cap on events retained per stream before the oldest (by {@code lastSeen}) are
     * pruned — generous but finite, matching {@link JpaDetectionRepository}'s own default for the
     * same reasoning (see MODULE.md's Retention section). Deliberately not the in-memory ring's
     * 500-per-stream cap, which exists to bound heap usage in a devsupport fallback, not to
     * express a real retention policy.
     */
    public static final int DEFAULT_RETENTION_LIMIT_PER_STREAM = 100_000;

    private final JpaOperations jpa;
    private final int retentionLimitPerStream;

    public JpaDetectionEventRepository(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, DEFAULT_RETENTION_LIMIT_PER_STREAM);
    }

    /**
     * @param retentionLimitPerStream cap on events retained per stream before the oldest are
     *                                pruned; must be positive. Same test-seam rationale as {@link
     *                                JpaDetectionRepository}'s two-argument constructor.
     */
    public JpaDetectionEventRepository(EntityManagerFactory entityManagerFactory, int retentionLimitPerStream) {
        this.jpa = new JpaOperations(entityManagerFactory);
        if (retentionLimitPerStream <= 0) {
            throw new IllegalArgumentException(
                    "retentionLimitPerStream must be positive: " + retentionLimitPerStream);
        }
        this.retentionLimitPerStream = retentionLimitPerStream;
    }

    @Override
    public DetectionEvent save(DetectionEvent event) {
        jpa.write(em -> {
            em.merge(DetectionEventMapper.toEntity(event));
            em.flush();
            pruneOldest(em, event.streamId().value());
            return null;
        });
        return event;
    }

    @Override
    public List<DetectionEvent> findRecent(Instant sinceInclusive, int limit) {
        return jpa.read(em -> {
            String jpql = "select e from DetectionEventEntity e"
                    + (sinceInclusive == null ? "" : " where e.lastSeen >= :since")
                    + " order by e.lastSeen desc";
            TypedQuery<DetectionEventEntity> query = em.createQuery(jpql, DetectionEventEntity.class);
            if (sinceInclusive != null) {
                query.setParameter("since", sinceInclusive);
            }
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(DetectionEventMapper::toDomain).toList();
    }

    @Override
    public List<DetectionEvent> findByStream(StreamId streamId, int limit) {
        return jpa.read(em -> {
            TypedQuery<DetectionEventEntity> query = em.createQuery(
                    "select e from DetectionEventEntity e where e.streamId = :streamId order by e.lastSeen desc",
                    DetectionEventEntity.class);
            query.setParameter("streamId", streamId.value());
            query.setMaxResults(limit);
            return query.getResultList();
        }).stream().map(DetectionEventMapper::toDomain).toList();
    }

    private void pruneOldest(EntityManager em, UUID streamId) {
        em.createNativeQuery("""
                DELETE FROM detection_events
                WHERE stream_id = ?1
                  AND id NOT IN (
                    SELECT id FROM detection_events WHERE stream_id = ?1 ORDER BY last_seen DESC LIMIT ?2
                  )
                """)
                .setParameter(1, streamId)
                .setParameter(2, retentionLimitPerStream)
                .executeUpdate();
    }
}
