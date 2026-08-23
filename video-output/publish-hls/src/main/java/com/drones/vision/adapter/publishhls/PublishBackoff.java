package com.drones.vision.adapter.publishhls;

/**
 * Per-stream exponential-backoff/outage bookkeeping for {@link MediamtxStreamPublisher}: while a
 * stream's mediamtx push is failing, frames are dropped and reconnect attempts are throttled
 * (doubling every failure, capped at {@value #MAX_BACKOFF_MS}ms) instead of attempting a real
 * connect for every single dropped frame during an outage.
 *
 * <p>Not thread-safe — mirrors {@link MediamtxStreamPublisher.StreamState}'s own per-stream,
 * single-writer contract ({@link com.drones.vision.perception.domain.port.StreamPublisherPort} guarantees
 * calls for one {@code streamId} are never concurrent).
 */
final class PublishBackoff {

    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 10_000L;

    private final long initialBackoffMs;
    private final long maxBackoffMs;

    private boolean outage;
    private long backoffMs;
    private long nextRetryAtEpochMs = 0L;

    PublishBackoff() {
        this(PublishSettings.Resilience.defaults());
    }

    /**
     * @param resilience {@code vision.publish.resilience.*} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave
     *                   F3) — replaces this class's own {@code INITIAL_BACKOFF_MS}/{@code
     *                   MAX_BACKOFF_MS} constants.
     */
    PublishBackoff(PublishSettings.Resilience resilience) {
        this.initialBackoffMs = resilience.initialBackoff().toMillis();
        this.maxBackoffMs = resilience.maxBackoff().toMillis();
        this.backoffMs = initialBackoffMs;
    }

    boolean readyToRetry() {
        return System.currentTimeMillis() >= nextRetryAtEpochMs;
    }

    void scheduleRetry() {
        nextRetryAtEpochMs = System.currentTimeMillis() + backoffMs;
        backoffMs = Math.min(backoffMs * 2, maxBackoffMs);
    }

    /** @return {@code true} the first time this is called for a given outage (so the caller logs once) */
    boolean beginOutage() {
        boolean first = !outage;
        outage = true;
        return first;
    }

    /** @return {@code true} if this call ends an active outage (so the caller can log recovery) */
    boolean endOutage() {
        boolean wasDown = outage;
        outage = false;
        backoffMs = initialBackoffMs;
        nextRetryAtEpochMs = 0L;
        return wasDown;
    }

    /**
     * Whether this stream is currently mid-outage — {@code video-publish}'s {@code
     * SubsystemStatusPort} plumbing (docs/plans/done/SYSTEM-STATUS-PLAN.md §4.2) reads this per
     * stream via {@link MediamtxStreamPublisher#streamsInOutage()} rather than duplicating the
     * outage flag.
     */
    boolean inOutage() {
        return outage;
    }
}
