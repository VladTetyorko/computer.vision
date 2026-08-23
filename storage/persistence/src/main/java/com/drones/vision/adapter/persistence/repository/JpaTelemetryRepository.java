package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.TelemetrySampleEntity;
import com.drones.vision.adapter.persistence.mapper.TelemetryMapper;
import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
 * docs/plans/done/MVP2-PLAN.md P-b's retention guard: every write that actually reaches Postgres prunes the
 * usage's oldest samples down to {@link #retentionLimitPerUsage} whenever the count exceeds it, via
 * one native {@code DELETE ... NOT IN (SELECT ... ORDER BY at DESC LIMIT n)} query in the same
 * transaction as the insert(s) it follows. An explicit {@code flush()} between the last {@code
 * persist} and the native delete is required — Hibernate does not know a hand-written native query
 * touches {@code telemetry_samples}, so without it the prune query would run against the
 * connection's pre-insert view of the table and could evict a sample from the very write it should
 * be keeping.
 *
 * <h2>Batching (docs/plans/done/SCALE-100-PLAN.md S4)</h2>
 * {@link #save} either writes straight through (the one/two-argument constructors — {@link
 * TelemetryBatchSettings#immediate()}, every pre-S4 caller's exact behavior) or buffers per usage
 * and flushes on {@link TelemetryBatchSettings}'s size-or-time bound (the three-argument
 * constructor). Buffering trades one thing for another, deliberately: {@code flush()}/prune runs
 * once per <em>batch</em> instead of once per <em>sample</em> — the whole reason this exists, since
 * a per-sample synchronous flush was a round trip on the hot ingest path — at the cost of a bounded
 * durability window: a sample lives only in this instance's heap until its batch flushes, so a
 * crash can lose up to one {@link TelemetryBatchSettings#batchWindowMillis()} worth of telemetry per
 * open usage. {@link #findByUsage} only ever sees flushed rows, so a read shortly after a
 * still-buffered write can lag by up to the same window too — a documented consequence of the same
 * trade-off, not a bug.
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
    private final TelemetryBatchSettings batchSettings;
    private final ScheduledExecutorService batchScheduler;
    private final ConcurrentHashMap<UUID, PendingBatch> pendingByUsage = new ConcurrentHashMap<>();

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
        this(entityManagerFactory, retentionLimitPerUsage, TelemetryBatchSettings.immediate());
    }

    /**
     * Same as the two-argument constructor, plus how {@link #save} batches its writes — see the
     * class javadoc's Batching section. {@link TelemetryBatchSettings#immediate()} reproduces the
     * two-argument constructor's own synchronous behavior exactly (what every pre-S4 caller, and
     * this class's own retention/round-trip tests, still get); production wiring is meant to move to
     * {@link TelemetryBatchSettings#defaults()} once {@code vision-app} binds {@code
     * vision.persistence.telemetry.*} to it.
     *
     * @param batchSettings how {@link #save} batches; never {@code null}. Only when it is
     *                      non-{@linkplain TelemetryBatchSettings#isImmediate() immediate} does this
     *                      constructor spin up the dedicated daemon scheduler {@link #save} needs to
     *                      honor the time bound — an immediate-mode instance (by far the common
     *                      case today) starts none.
     */
    public JpaTelemetryRepository(EntityManagerFactory entityManagerFactory, int retentionLimitPerUsage,
                                   TelemetryBatchSettings batchSettings) {
        this.jpa = new JpaOperations(entityManagerFactory);
        if (retentionLimitPerUsage <= 0) {
            throw new IllegalArgumentException(
                    "retentionLimitPerUsage must be positive: " + retentionLimitPerUsage);
        }
        this.retentionLimitPerUsage = retentionLimitPerUsage;
        this.batchSettings = Objects.requireNonNull(batchSettings, "batchSettings must not be null");
        this.batchScheduler = batchSettings.isImmediate() ? null : Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "telemetry-batch-flush");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void save(UsageId usageId, Telemetry telemetry) {
        if (batchSettings.isImmediate()) {
            persistBatch(usageId, List.of(telemetry));
            return;
        }
        List<Telemetry> toFlush = new ArrayList<>();
        pendingByUsage.compute(usageId.value(), (key, batch) -> {
            PendingBatch pending = batch == null ? new PendingBatch() : batch;
            pending.samples.add(telemetry);
            if (pending.samples.size() == 1) {
                // first sample of a fresh batch: arm the time bound so it flushes even if nothing
                // else ever arrives for this usage again.
                pending.flushTask = batchScheduler.schedule(() -> flushIfPending(usageId),
                        batchSettings.batchWindowMillis(), TimeUnit.MILLISECONDS);
            }
            if (pending.samples.size() < batchSettings.batchSizeSamples()) {
                return pending;
            }
            toFlush.addAll(pending.samples);
            cancelPendingFlush(pending);
            return null; // size bound hit: drained, so drop the entry rather than keep an empty one
        });
        if (!toFlush.isEmpty()) {
            persistBatch(usageId, toFlush);
        }
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

    /**
     * How many usages currently hold buffered, not-yet-durable samples — i.e. how much this instance
     * would lose to a {@code kill -9} right now. Always {@code 0} in immediate mode.
     *
     * <p>Exposed because it is the one number that makes the batching trade-off observable from
     * outside, and because it is the invariant most likely to rot: a batch that is drained but not
     * removed leaves this climbing forever, one entry per flight ever flown, which is precisely the
     * unbounded-map defect (docs/plans/done/SCALE-100-PLAN.md fact 2f) this plan exists to remove.
     */
    public int pendingBatchCount() {
        return pendingByUsage.size();
    }

    /** Fired by {@link #batchScheduler} once {@link TelemetryBatchSettings#batchWindowMillis()} elapses. */
    private void flushIfPending(UsageId usageId) {
        List<Telemetry> toFlush = new ArrayList<>();
        pendingByUsage.compute(usageId.value(), (key, batch) -> {
            if (batch != null) {
                toFlush.addAll(batch.samples);
                cancelPendingFlush(batch);
            }
            return null; // drained (or already gone): leave no entry behind either way
        });
        if (toFlush.isEmpty()) {
            return; // the size bound already flushed this batch first
        }
        persistBatch(usageId, toFlush);
    }

    /** One transaction: every sample in {@code samples} persisted, one flush, one prune. */
    private void persistBatch(UsageId usageId, List<Telemetry> samples) {
        jpa.write(em -> {
            for (Telemetry sample : samples) {
                em.persist(TelemetryMapper.toEntity(usageId, sample));
            }
            em.flush();
            pruneOldest(em, usageId.value());
            return null;
        });
    }

    private static void cancelPendingFlush(PendingBatch batch) {
        if (batch.flushTask != null) {
            batch.flushTask.cancel(false);
            batch.flushTask = null;
        }
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

    /**
     * One usage's buffered-but-not-yet-durable samples plus the scheduled time-bound flush task, if
     * any is currently armed.
     *
     * <p>Never touched outside a {@link java.util.concurrent.ConcurrentHashMap#compute} on {@link
     * #pendingByUsage}, which is what makes both fields safe without their own lock: {@code compute}
     * serializes every writer and the flusher on the same key, so a batch cannot be appended to
     * while it is being drained. Draining always returns {@code null} from that lambda, so an
     * emptied batch is *evicted* rather than left behind — this map is on the telemetry hot path and
     * keyed by usage, so keeping empty entries would grow it for the life of the JVM, one per flight
     * ever flown (docs/plans/done/SCALE-100-PLAN.md fact 2f is the same defect in {@code
     * LiveUpdateRegistry}, which S2 had to fix). The next {@link #save} simply creates a fresh one.
     *
     * <p>The DB write itself deliberately happens <em>outside</em> the lambda: {@code compute} holds
     * a bin lock, and a JDBC round trip under it would serialize unrelated usages.
     */
    private static final class PendingBatch {
        private final List<Telemetry> samples = new ArrayList<>();
        private ScheduledFuture<?> flushTask;
    }
}
