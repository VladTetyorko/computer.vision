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

    /** A live {@link #repeat} registration. */
    interface Handle {

        /** Stops this task from firing again. Idempotent. */
        void close();
    }
}
