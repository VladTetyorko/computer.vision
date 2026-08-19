package com.drones.vision.adapter.tiles;

import java.util.concurrent.TimeUnit;

/**
 * A shared token-interval rate limiter: at most {@code requestsPerSecond} outbound requests per
 * second across every thread calling {@link #acquire()} on the same instance — ported from the
 * Wave 0 spike's {@code cv-service/spikes/geo/tiles.py}'s {@code _RateLimiter} (harvested from
 * {@code feat/visual-geo}'s {@code adapter-tiles}, docs/plans/active/VISUAL-GEO-V2-PLAN.md §1.3).
 * One instance is shared by every {@link HttpTileSource#fetch} call, so the limit holds regardless
 * of how many threads a caller uses to fetch a region's tiles concurrently ({@code
 * vision.geo.visual.tiles.requests-per-second}).
 *
 * <p>Only the bookkeeping (computing this caller's slot and advancing the shared cursor) happens
 * under the lock; the actual sleep happens outside it, so callers waiting on different slots don't
 * serialize on each other's sleep — same structure as the Python original's {@code with self._lock:
 * ...} block ending before {@code time.sleep(delay)}.
 */
final class TileRateLimiter {

    private final long intervalNanos;
    private final Object lock = new Object();
    private long nextSlotNanos;

    TileRateLimiter(double requestsPerSecond) {
        this.intervalNanos = requestsPerSecond > 0 ? (long) (1_000_000_000L / requestsPerSecond) : 0L;
    }

    /**
     * Blocks the calling thread until this caller's turn, per the configured rate.
     *
     * @throws InterruptedException if interrupted while waiting for a slot
     */
    void acquire() throws InterruptedException {
        if (intervalNanos <= 0) {
            return;
        }
        long delayNanos;
        synchronized (lock) {
            long now = System.nanoTime();
            long start = Math.max(now, nextSlotNanos);
            nextSlotNanos = start + intervalNanos;
            delayNanos = start - now;
        }
        if (delayNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
        }
    }
}
