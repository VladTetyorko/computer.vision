package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.DetectionResultEntity;
import com.drones.vision.adapter.persistence.mapper.DetectionResultMapper;
import com.drones.vision.domain.model.DetectionQuery;
import com.drones.vision.domain.model.DetectionResult;
import com.drones.vision.domain.port.out.DetectionRepositoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.TypedQuery;

import java.util.List;
import java.util.UUID;

/**
 * {@link DetectionRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} always {@code persist}s a brand-new row — results are immutable historical
 * records per the port's contract, and {@code DetectionResult} carries no id to merge by (see
 * {@code DetectionResultEntity}'s javadoc). {@link #query} filters {@code streamId}/{@code
 * from}/{@code to} in SQL (indexed on {@code (stream_id, captured_at)}, see {@code
 * V3__history.sql}) and sorts newest-first there too, but — like {@code
 * InMemoryDetectionRepository} — filters {@code label} in Java after fetch, since a detection's
 * labels live inside the {@code detections} jsonb column, not a queryable column of their own (see
 * {@code DetectionResultEntity}'s javadoc for why a normalized child table wasn't worth it).
 * {@code limit} is applied last, after the label filter, exactly matching the in-memory reference
 * implementation's filter→sort→limit order. Also mirrors one existing quirk of that
 * implementation rather than "fixing" it: {@link DetectionQuery#to()}'s javadoc calls it an
 * <em>exclusive</em> upper bound, but the in-memory implementation actually treats it as inclusive
 * ({@code !capturedAt.isAfter(to)}) — this class does too, per this task's "match the reference
 * implementation's exact semantics" brief (see MODULE.md).
 *
 * <h2>Retention</h2>
 * docs/MVP2-PLAN.md P-b's retention guard, same mechanism as {@link
 * com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository} but keyed by {@code
 * streamId} (the only grouping key {@link DetectionResult}/{@link DetectionQuery} actually
 * carry — there is no {@code usageId} on a detection): {@link #save} prunes a stream's oldest
 * results down to {@link #retentionLimitPerStream} whenever the count exceeds it, via one native
 * delete query per write in the same transaction as the insert, after an explicit {@code flush()}
 * for the same reason {@code JpaTelemetryRepository} needs one.
 */
public final class JpaDetectionRepository implements DetectionRepositoryPort {

    /**
     * Default cap on results retained per stream before the oldest are pruned — generous (at
     * 10fps, ~2.75 hours of continuous detections for one stream) but finite. See MODULE.md's
     * Retention section for the honest tradeoffs.
     */
    public static final int DEFAULT_RETENTION_LIMIT_PER_STREAM = 100_000;

    private final JpaOperations jpa;
    private final int retentionLimitPerStream;

    public JpaDetectionRepository(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, DEFAULT_RETENTION_LIMIT_PER_STREAM);
    }

    /**
     * @param retentionLimitPerStream cap on results retained per stream before the oldest are
     *                                pruned; must be positive. Same test-seam rationale as {@link
     *                                com.drones.vision.adapter.persistence.repository.JpaTelemetryRepository}'s
     *                                two-argument constructor.
     */
    public JpaDetectionRepository(EntityManagerFactory entityManagerFactory, int retentionLimitPerStream) {
        this.jpa = new JpaOperations(entityManagerFactory);
        if (retentionLimitPerStream <= 0) {
            throw new IllegalArgumentException(
                    "retentionLimitPerStream must be positive: " + retentionLimitPerStream);
        }
        this.retentionLimitPerStream = retentionLimitPerStream;
    }

    @Override
    public void save(DetectionResult result) {
        jpa.write(em -> {
            em.persist(DetectionResultMapper.toEntity(result));
            em.flush();
            pruneOldest(em, result.streamId().value());
            return null;
        });
    }

    @Override
    public List<DetectionResult> query(DetectionQuery query) {
        List<DetectionResultEntity> candidates = jpa.read(em -> {
            StringBuilder jpql = new StringBuilder("select d from DetectionResultEntity d where 1 = 1");
            if (query.streamId() != null) {
                jpql.append(" and d.streamId = :streamId");
            }
            if (query.from() != null) {
                jpql.append(" and d.capturedAt >= :from");
            }
            if (query.to() != null) {
                jpql.append(" and d.capturedAt <= :to");
            }
            jpql.append(" order by d.capturedAt desc");

            TypedQuery<DetectionResultEntity> typedQuery =
                    em.createQuery(jpql.toString(), DetectionResultEntity.class);
            if (query.streamId() != null) {
                typedQuery.setParameter("streamId", query.streamId().value());
            }
            if (query.from() != null) {
                typedQuery.setParameter("from", query.from());
            }
            if (query.to() != null) {
                typedQuery.setParameter("to", query.to());
            }
            return typedQuery.getResultList();
        });

        return candidates.stream()
                .map(DetectionResultMapper::toDomain)
                .filter(r -> query.label() == null
                        || r.detections().stream().anyMatch(d -> query.label().equals(d.label())))
                .limit(query.limit())
                .toList();
    }

    private void pruneOldest(EntityManager em, UUID streamId) {
        em.createNativeQuery("""
                DELETE FROM detection_results
                WHERE stream_id = ?1
                  AND id NOT IN (
                    SELECT id FROM detection_results WHERE stream_id = ?1 ORDER BY captured_at DESC LIMIT ?2
                  )
                """)
                .setParameter(1, streamId)
                .setParameter(2, retentionLimitPerStream)
                .executeUpdate();
    }
}
