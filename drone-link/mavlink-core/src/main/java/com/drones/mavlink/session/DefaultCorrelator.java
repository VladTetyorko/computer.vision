package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The one {@link Correlator} implementation. Backed by a {@link ConcurrentHashMap}, so several
 * links' reader threads may {@link #offer} concurrently while a caller thread awaits/cancels.
 *
 * <h2>Registration is exclusive, not last-write-wins</h2>
 * {@link #await} uses {@link Map#putIfAbsent} and throws if a live waiter already occupies
 * {@code key}, rather than {@link Map#put}, which would silently orphan the first waiter's future
 * forever (it would never be completed, since {@link #offer} only ever completes the one future
 * currently mapped to a key) — see this class's own {@link Correlator} javadoc.
 *
 * <h2>Extraction lives elsewhere, and this class is closed to it</h2>
 * {@link #offer} is how {@link MavlinkSession} feeds every decoded frame to this correlator, but
 * <i>which</i> key a frame carries is {@link CorrelationKeys}' business (MISSIONS-PLAN.md D6).
 * Supporting a new correlated message type is a row in that table; it is never an edit here. What
 * this class owns is only the registry mechanics — exclusive registration, completion, self-cleanup.
 */
public final class DefaultCorrelator implements Correlator {

    private final Map<MatchKey, CompletableFuture<MavFrame>> waiters = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<MavFrame> await(MatchKey key, Duration timeout) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(timeout, "timeout");
        CompletableFuture<MavFrame> future = new CompletableFuture<>();
        if (waiters.putIfAbsent(key, future) != null) {
            throw new IllegalStateException(
                    "A live await is already registered for " + key + " -- cancel it before registering "
                            + "another (silently replacing it would leak the first waiter forever)");
        }
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        // Self-cleaning: whatever way this future ends (matched, timed out, cancelled), stop
        // occupying the key so a later await for the same key is not rejected by a waiter nobody
        // is waiting on anymore. Uses the two-arg remove so this never evicts a *different* future
        // that a fresh await might have registered for the same key in the meantime.
        future.whenComplete((frame, error) -> waiters.remove(key, future));
        return future;
    }

    @Override
    public void cancel(MatchKey key) {
        Objects.requireNonNull(key, "key");
        CompletableFuture<MavFrame> removed = waiters.remove(key);
        if (removed != null) {
            removed.cancel(false);
        }
    }

    /**
     * Called by {@link MavlinkSession} for every decoded frame, after {@link PeerDirectory}/
     * {@link LinkHealth} are updated and before {@link Dispatcher} sees it — so a waiter can never
     * miss a reply because a slow dispatcher handler ran first.
     */
    void offer(MavFrame frame) {
        MatchKey key = CorrelationKeys.keyFor(frame);
        if (key == null) {
            return;
        }
        CompletableFuture<MavFrame> future = waiters.remove(key);
        if (future != null) {
            future.complete(frame); // outside any lock -- ConcurrentHashMap#remove holds none across this call
        }
    }
}
