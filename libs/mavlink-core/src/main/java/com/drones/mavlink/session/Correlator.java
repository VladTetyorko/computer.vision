package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * The join between MAVLink's outbound request path and its unsolicited-inbound reply path: a
 * pending-request registry that completes a future when a matching reply is decoded.
 *
 * <h2>The two rules that matter</h2>
 * <ul>
 *   <li>{@link #await} must be called <b>before</b> the request is sent, and the returned future's
 *       key must always be released via {@link #cancel} in a {@code finally} block — a waiter
 *       registered and never removed both leaks and permanently shadows every future reply for
 *       that key.</li>
 *   <li>A repeat {@link #await} on a key that already has a live (incomplete, uncancelled) waiter
 *       fails loudly rather than silently replacing it — {@code Map#put} semantics on the previous
 *       waiter is exactly the bug this must not repeat (a documented sharp edge of today's
 *       {@code CommandAckRegistry}).</li>
 * </ul>
 */
public interface Correlator {

    /**
     * Registers interest in the next frame matching {@code key}, completing the returned future
     * with it. If no matching frame arrives within {@code timeout}, the future completes
     * exceptionally with a {@link java.util.concurrent.TimeoutException}.
     *
     * @throws IllegalStateException if a live waiter is already registered for {@code key}
     */
    CompletableFuture<MavFrame> await(MatchKey key, Duration timeout);

    /**
     * Releases a waiter registered via {@link #await}, if still live. Idempotent — safe to call
     * whether the future already completed (naturally or by timeout), was already cancelled, or
     * was never registered at all.
     */
    void cancel(MatchKey key);
}
