package com.drones.vision.adapter.publishhls;

import java.time.Duration;

/**
 * Per-stream docs/plans/done/MVP2-PLAN.md V-c capture→encode lag bookkeeping for {@link
 * MediamtxStreamPublisher}: a rolling {@link LagTracker} window of recent lag samples, plus the
 * gate ({@link #shouldLogLag}) that limits the periodic p50/p95 summary log to at most once every
 * {@value #LAG_LOG_INTERVAL_MILLIS}ms per stream.
 *
 * <p>Not thread-safe — mirrors {@link MediamtxStreamPublisher.StreamState}'s own per-stream,
 * single-writer contract ({@link com.drones.vision.perception.domain.port.StreamPublisherPort} guarantees
 * calls for one {@code streamId} are never concurrent).
 */
final class PublishDiagnostics {

    /**
     * docs/plans/done/MVP2-PLAN.md V-c: ring-buffer capacity for {@link #lagTracker}. 150 samples covers
     * several seconds' worth of frames at typical 15-30fps sources — enough for a stable p50/p95
     * read between periodic log lines without holding an unbounded or needlessly large history.
     */
    static final int LAG_TRACKER_WINDOW_SIZE = 150;
    /** docs/plans/done/MVP2-PLAN.md V-c: minimum wall-clock gap between a stream's periodic capture→encode lag summary logs. */
    static final long LAG_LOG_INTERVAL_MILLIS = Duration.ofSeconds(30).toMillis();

    // Package-private, not private: MediamtxStreamPublisher.writeFrame reads/writes it directly,
    // no getter ceremony needed for a per-stream, single-writer field (mirrors StreamState's own
    // `recorder` field visibility).
    final LagTracker lagTracker = new LagTracker(LAG_TRACKER_WINDOW_SIZE);
    private long nextLagLogAtEpochMs = 0L;

    /**
     * Gate for the periodic per-stream capture→encode lag summary log (docs/plans/done/MVP2-PLAN.md V-c):
     * {@code true} at most once per {@value #LAG_LOG_INTERVAL_MILLIS}ms of wall-clock time, and
     * never on the very first call — that call only establishes the baseline, since logging
     * immediately would report a single-sample "p50/p95" before the rolling window holds anything
     * meaningful. Millisecond epoch, not {@link java.time.Instant}, matching {@link
     * PublishBackoff}'s own backoff-timing idiom.
     */
    boolean shouldLogLag(long nowEpochMs) {
        if (nextLagLogAtEpochMs == 0L) {
            nextLagLogAtEpochMs = nowEpochMs + LAG_LOG_INTERVAL_MILLIS;
            return false;
        }
        if (nowEpochMs < nextLagLogAtEpochMs) {
            return false;
        }
        nextLagLogAtEpochMs = nowEpochMs + LAG_LOG_INTERVAL_MILLIS;
        return true;
    }
}
