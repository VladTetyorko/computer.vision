package com.drones.vision.adapter.persistence.repository;

/**
 * How {@link JpaTelemetryRepository#save} batches its writes (docs/plans/done/SCALE-100-PLAN.md S4).
 * Framework-free, matching {@code PersistencePoolSettings}'s own convention: {@code vision-app}
 * binds {@code vision.persistence.telemetry.*} and passes this record in, rather than this module
 * reading Spring configuration itself.
 *
 * <p>A sample becomes durable the instant {@link #batchSizeSamples()} accumulate for one usage, or
 * {@link #batchWindowMillis()} have elapsed since the first still-buffered one for that usage,
 * whichever comes first. Until then it exists only in this repository instance's own heap — a
 * crash (or an unclean restart) loses whatever is still buffered for every open usage, bounded to
 * at most one window's worth per usage (docs/plans/done/SCALE-100-PLAN.md &sect;5 S4's stated
 * trade-off; CLAUDE.md rule 9's "newest data wins" intent is why the default window is kept small
 * rather than zero).
 *
 * @param batchSizeSamples  number of samples buffered for one usage before a flush is forced
 *                          regardless of the time bound; must be at least 1. Default {@value
 *                          #DEFAULT_BATCH_SIZE_SAMPLES} is a safety ceiling for an unusually
 *                          high-rate source — a typical ~1Hz flight-controller feed produces far
 *                          fewer samples than this within one {@link #batchWindowMillis()}, so in
 *                          practice the time bound is what decides when a batch actually flushes
 * @param batchWindowMillis how long a buffered sample may stay undurable before it flushes on its
 *                          own; must not be negative. Default {@value #DEFAULT_BATCH_WINDOW_MILLIS}
 *                          keeps the crash-loss window comfortably under the plan's 250ms ceiling.
 *                          {@code 0} disables buffering entirely — every {@link
 *                          JpaTelemetryRepository#save} persists and flushes synchronously before
 *                          returning, today's exact behavior, for a deployment that wants zero loss
 *                          over ingest throughput
 */
public record TelemetryBatchSettings(int batchSizeSamples, long batchWindowMillis) {

    public static final int DEFAULT_BATCH_SIZE_SAMPLES = 100;
    public static final long DEFAULT_BATCH_WINDOW_MILLIS = 200L;

    public TelemetryBatchSettings {
        if (batchSizeSamples < 1) {
            throw new IllegalArgumentException("batchSizeSamples must be at least 1, was " + batchSizeSamples);
        }
        if (batchWindowMillis < 0) {
            throw new IllegalArgumentException("batchWindowMillis must not be negative, was " + batchWindowMillis);
        }
    }

    /** The defaults documented on each component above. */
    public static TelemetryBatchSettings defaults() {
        return new TelemetryBatchSettings(DEFAULT_BATCH_SIZE_SAMPLES, DEFAULT_BATCH_WINDOW_MILLIS);
    }

    /**
     * No buffering: every {@link JpaTelemetryRepository#save} call persists and flushes before
     * returning, exactly how this repository behaved before docs/plans/done/SCALE-100-PLAN.md S4. What the
     * one/two-argument {@link JpaTelemetryRepository} constructors use, so every pre-existing
     * caller (and test) keeps its synchronous read-after-write behavior unchanged.
     */
    public static TelemetryBatchSettings immediate() {
        return new TelemetryBatchSettings(1, 0);
    }

    /** {@code true} when {@link #batchWindowMillis()} is {@code 0} — see that component's javadoc. */
    public boolean isImmediate() {
        return batchWindowMillis == 0;
    }
}
