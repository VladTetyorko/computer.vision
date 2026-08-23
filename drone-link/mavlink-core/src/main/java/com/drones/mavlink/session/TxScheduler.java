package com.drones.mavlink.session;

import java.time.Duration;

/**
 * Runs named periodic TX tasks (heartbeat emission, RC override relay, ...) on one shared thread
 * pool rather than one hand-rolled thread loop per feature (plan §4 DRY — this replaces three
 * near-identical thread-lifecycle implementations in {@code adapter-mavlink}: {@code
 * MavlinkSocketHub}, {@code FeedRuntime}, {@code RcLinkRuntime}).
 */
public interface TxScheduler {

    /**
     * Runs {@code task} every {@code period}, starting immediately, until the returned
     * {@link Handle} is closed. A {@code task} that throws is logged and the schedule continues —
     * one bad periodic task must never silently stop firing without a trace, and must never take
     * down another task sharing the same scheduler.
     *
     * @param name used only for diagnostics (thread naming, log messages) — not an identity key
     */
    Handle repeat(String name, Duration period, Runnable task);

    /**
     * Runs {@code task} once, as soon as a pool thread is free — the latency-sensitive counterpart
     * to {@link #repeat} for a TX path that must not wait for the next tick of a periodic task
     * (docs/plans/done/RC-LATENCY-PLAN.md §2 A: a stick that moves 1 ms after a tick should not
     * pay a full period for a clock that had no reason to be where it was). Same catch-and-log
     * contract as {@link #repeat} — a task that throws is logged and never reaches the pool — and
     * the same "already shut down" tolerance: a submission racing scheduler shutdown is dropped,
     * not thrown, since callers submit from threads that must not fail on teardown.
     *
     * <p>No {@link Handle} is returned: a one-shot that has already been handed to the pool cannot
     * usefully be cancelled, and every caller re-checks its own state when the task actually runs.
     *
     * @param name used only for diagnostics (log messages) — not an identity key
     */
    void submit(String name, Runnable task);

    /** A live {@link #repeat} registration. */
    interface Handle {

        /** Stops this task from firing again. Idempotent. */
        void close();
    }
}
