package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.adapter.persistence.mapper.TelemetryMapper;
import com.drones.vision.domain.model.Telemetry;
import com.drones.vision.domain.model.UsageId;
import com.drones.vision.domain.port.out.TelemetryRepositoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.UUID;

/**
 * {@link TelemetryRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}).
 *
 * <p>{@link #save} always {@code persist}s a brand-new row (never {@code merge}s) — samples are
 * immutable historical records per the port's contract, so there is never an existing row to
 * update, and {@code Telemetry} itself carries no id to merge by (see {@code TelemetrySampleEntity}'s
 * javadoc for the synthetic-id rationale). {@link #findByUsage} mirrors {@code
 * InMemoryTelemetryRepository}'s exact (if slightly surprising) semantics: {@code limit} selects
 * the <strong>earliest</strong> {@code limit} samples for the usage, not the most recent — the
 * in-memory reference implementation's {@code list.stream().limit(n)} takes the first {@code n} of
 * an append-ordered list, i.e. the oldest first, and this class reproduces that with {@code order
 * by at asc} rather than "fixing" it to newest-first, per this task's "match the in-memory
 * semantics exactly" brief. (Worth flagging for a future task: this is arguably not what {@code
 * AssetController}'s {@code GET /api/usages/{id}/telemetry?limit=100} actually wants for a
 * long-running flight — see MODULE.md.)
 *
 * <h2>Retention</h2>
 * docs/plans/done/MVP2-PLAN.md P-b's retention guard: {@link #save} prunes the usage's oldest samples down to
 * {@link #retentionLimitPerUsage} whenever the count exceeds it, via one native {@code DELETE ...
 * NOT IN (SELECT ... ORDER BY at DESC LIMIT n)} query per write, in the same transaction as the
 * insert. An explicit {@code flush()} between the {@code persist} and the native delete is required
 * — Hibernate does not know a hand-written native query touches {@code telemetry_samples}, so
 * without it the prune query would run against the connection's pre-insert view of the table and
 * could evict the very sample just being appended once the usage is already at capacity.
 */
public final class JpaTelemetryRepository implements TelemetryRepositoryPort {

    /**
     * Default cap on samples retained per usage before the oldest are pruned — generous (at 1Hz,
     * ~27 hours of continuous telemetry for one usage) but finite, so a usage nobody ever stops
     * (e.g. a forgotten dev-mode stream) cannot grow this table unboundedly. See MODULE.md's
     * Retention section for the honest tradeoffs.
     */
    public static final int DEFAULT_RETENTION_LIMIT_PER_USAGE = 100_000;

    private final JpaOperations jpa;
    private final int retentionLimitPerUsage;

    public JpaTelemetryRepository(EntityManagerFactory entityManagerFactory) {
        this(entityManagerFactory, DEFAULT_RETENTION_LIMIT_PER_USAGE);
    }

    /**
     * @param retentionLimitPerUsage cap on samples retained per usage before the oldest are
     *                               pruned; must be positive. Package-visible-in-practice knob for
     *                               tests that need to exercise pruning without inserting {@value
     *                               #DEFAULT_RETENTION_LIMIT_PER_USAGE} rows first; production
     *                               wiring uses the one-argument constructor's generous default.
     */
    public JpaTelemetryRepository(EntityManagerFactory entityManagerFactory, int retentionLimitPerUsage) {
        this.jpa = new JpaOperations(entityManagerFactory);
        if (retentionLimitPerUsage <= 0) {
            throw new IllegalArgumentException(
                    "retentionLimitPerUsage must be positive: " + retentionLimitPerUsage);
        }
        this.retentionLimitPerUsage = retentionLimitPerUsage;
    }

    @Override
    public void save(UsageId usageId, Telemetry telemetry) {
        jpa.write(em -> {
            em.persist(TelemetryMapper.toEntity(usageId, telemetry));
            em.flush();
            pruneOldest(em, usageId.value());
            return null;
        });
    }

    @Override
    public List<Telemetry> findByUsage(UsageId usageId, int limit) {
        return jpa.read(em -> em.createQuery(
                        "select t from TelemetrySampleEntity t where t.usageId = :usageId order by t.at asc",
                        TelemetrySampleEntity.class)
                        .setParameter("usageId", usageId.value())
                        .setMaxResults(limit)
                        .getResultList())
                .stream()
                .map(TelemetryMapper::toDomain)
                .toList();
    }

    private void pruneOldest(EntityManager em, UUID usageId) {
        em.createNativeQuery("""
                DELETE FROM telemetry_samples
                WHERE usage_id = ?1
                  AND id NOT IN (
                    SELECT id FROM telemetry_samples WHERE usage_id = ?1 ORDER BY at DESC LIMIT ?2
                  )
                """)
                .setParameter(1, usageId)
                .setParameter(2, retentionLimitPerUsage)
                .executeUpdate();
    }
}
