package com.drones.vision.api.ratelimit;

import java.util.concurrent.TimeUnit;

/**
 * One principal's request budget for {@link RateLimitFilter} — capacity and average refill rate
 * are both {@code permitsPerMinute}: a full minute's allotment may be spent in a burst, then the
 * bucket refills continuously at that same rate rather than resetting on a fixed clock tick.
 *
 * <p>Deliberately takes {@code now} as a parameter on every call rather than reading {@link
 * System#nanoTime()} itself — {@link RateLimitFilter} owns the one time source (real in
 * production, fake in tests), so this class stays a pure function of its own state and the given
 * instant, testable without waiting on real elapsed time.
 *
 * <p>Every method is {@code synchronized}: the critical section is pure in-memory arithmetic,
 * never I/O or another lock, so this carries none of the virtual-thread carrier-pinning risk
 * docs/plans/active/SCALE-100-CONTEXT.md §7 flags for a blocking call held under {@code
 * synchronized} — the same reasoning {@code LiveRingBuffer}'s own javadoc gives for its
 * synchronized, in-memory-only methods.
 */
final class TokenBucket {

    private final double capacity;
    private final double tokensPerNano;
    private double tokens;
    private long lastTouchedNanos;

    /**
     * @param permitsPerMinute bucket capacity and average refill rate; must be positive
     * @param nowNanos         the instant this bucket is created, already full
     */
    TokenBucket(int permitsPerMinute, long nowNanos) {
        this.capacity = permitsPerMinute;
        this.tokensPerNano = permitsPerMinute / (double) TimeUnit.MINUTES.toNanos(1);
        this.tokens = capacity;
        this.lastTouchedNanos = nowNanos;
    }

    /**
     * Refills for the elapsed time since the last call, then consumes one token if available.
     *
     * @param nowNanos the current instant
     * @return {@code true} if a token was consumed (caller may proceed), {@code false} if the
     *         bucket is empty right now (caller is over budget)
     */
    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens < 1.0) {
            return false;
        }
        tokens -= 1.0;
        return true;
    }

    /**
     * @param cutoffNanos an instant in the past
     * @return {@code true} if this bucket has not been touched (via {@link #tryConsume}) since
     *         before {@code cutoffNanos} — {@link RateLimitFilter#evictIdleBuckets()} removes such
     *         buckets so the map they live in stays bounded by recently-active principals, not
     *         every principal ever seen since boot
     */
    synchronized boolean idleSince(long cutoffNanos) {
        return lastTouchedNanos < cutoffNanos;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastTouchedNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * tokensPerNano);
            lastTouchedNanos = nowNanos;
        }
    }
}
