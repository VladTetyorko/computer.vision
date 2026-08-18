package com.drones.vision.perception.application.pipeline;

/**
 * How {@link UsageTracker#applySample} coalesces its {@code AssetUsage} summary-counter write
 * (docs/plans/active/SCALE-100-PLAN.md S4). Framework-free like every other {@code *Settings} record
 * in this package ({@link AdaptiveRateSettings}, {@code StreamPipelineSettings}) — {@code vision-app}
 * binds {@code vision.persistence.telemetry.*} and passes an instance in, this context module never
 * reads Spring configuration itself.
 *
 * <p>{@link UsageTracker} writes each telemetry sample twice: once durably, via {@code
 * TelemetryRepositoryPort#save}, and once as a cheap running summary ({@code startPosition}/{@code
 * lastPosition}/{@code sampleCount}) via {@code AssetUsageRepositoryPort#save}. The second write is a
 * counter, not a historical record — losing the last one before a crash only means the summary is
 * briefly stale, never wrong, since the next sample recomputes it from the same in-memory {@code
 * Tracking} state it was folded into. Coalescing it onto the same size-or-time
 * bound as {@code TelemetryBatchSettings} (docs/plans/active/SCALE-100-PLAN.md &sect;5 S4 item 3) turns N
 * summary writes into one for a fast-arriving burst, at the cost of the summary lagging the true
 * counts by up to {@link #batchWindowMillis()} — bounded exactly like {@code
 * TelemetryBatchSettings}'s own durability window, and for the same reason (CLAUDE.md rule 9's
 * "newest data wins" intent is why the default window is kept small rather than zero).
 *
 * @param batchSizeSamples  number of folded samples buffered for one usage before the summary is
 *                          written regardless of the time bound; must be at least 1. See {@code
 *                          TelemetryBatchSettings#batchSizeSamples()} for why this ceiling rarely
 *                          matters in practice against a typical flight-controller feed's rate.
 * @param batchWindowMillis how long a folded-but-unwritten summary may lag before it is written on
 *                          its own; must not be negative. Default {@value #DEFAULT_BATCH_WINDOW_MILLIS}
 *                          matches {@code TelemetryBatchSettings#DEFAULT_BATCH_WINDOW_MILLIS} — the
 *                          two are meant to be wired from the same {@code
 *                          vision.persistence.telemetry.batch-window} property so one number governs
 *                          both write paths. {@code 0} disables coalescing entirely: every sample
 *                          writes its summary synchronously before {@code applySample} returns,
 *                          today's exact behavior.
 */
public record UsageSummaryBatchSettings(int batchSizeSamples, long batchWindowMillis) {

    public static final int DEFAULT_BATCH_SIZE_SAMPLES = 100;
    public static final long DEFAULT_BATCH_WINDOW_MILLIS = 200L;

    public UsageSummaryBatchSettings {
        if (batchSizeSamples < 1) {
            throw new IllegalArgumentException("batchSizeSamples must be at least 1, was " + batchSizeSamples);
        }
        if (batchWindowMillis < 0) {
            throw new IllegalArgumentException("batchWindowMillis must not be negative, was " + batchWindowMillis);
        }
    }

    /** The defaults documented on each component above. */
    public static UsageSummaryBatchSettings defaults() {
        return new UsageSummaryBatchSettings(DEFAULT_BATCH_SIZE_SAMPLES, DEFAULT_BATCH_WINDOW_MILLIS);
    }

    /**
     * No coalescing: every sample writes its summary synchronously, exactly how {@link
     * UsageTracker#applySample} behaved before docs/plans/active/SCALE-100-PLAN.md S4. What every
     * pre-S4 constructor uses, so every pre-existing caller (and test) keeps its synchronous
     * one-save-per-sample behavior unchanged.
     */
    public static UsageSummaryBatchSettings immediate() {
        return new UsageSummaryBatchSettings(1, 0);
    }

    /** {@code true} when {@link #batchWindowMillis()} is {@code 0} — see that component's javadoc. */
    public boolean isImmediate() {
        return batchWindowMillis == 0;
    }
}
