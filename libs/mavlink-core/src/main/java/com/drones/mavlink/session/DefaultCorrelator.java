package com.drones.mavlink.session;

import com.drones.mavlink.codec.MavFrame;

import io.dronefleet.mavlink.common.CommandAck;

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
 * <h2>Extraction is per-message-type and, today, one case</h2>
 * {@link #offer} is how {@link MavlinkSession} feeds every decoded frame to this correlator. Only
 * {@code COMMAND_ACK} is wired up — the sole Family-A acknowledgement in scope before W6 (plan §4
 * non-goals: Mission/Parameter/FTP services are gated on the seam proving itself first). Matched on
 * {@code (origin sysid, command id)} only: {@code targetSystem}/{@code targetComponent} are wire
 * extension fields and are not reliably populated (plan §2.3), so they are never consulted.
 * Extending this to Mission/FTP/Parameter acks in W6 means adding a branch here — see this module's
 * MODULE.md for why that is an accepted, documented coupling rather than an oversight.
 */
public final class DefaultCorrelator implements Correlator {

    /** {@code COMMAND_ACK}'s wire message id (MAVLink common.xml). */
    private static final int COMMAND_ACK_MESSAGE_ID = 77;

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
        MatchKey key = extractKey(frame);
        if (key == null) {
            return;
        }
        CompletableFuture<MavFrame> future = waiters.remove(key);
        if (future != null) {
            future.complete(frame); // outside any lock -- ConcurrentHashMap#remove holds none across this call
        }
    }

    private static MatchKey extractKey(MavFrame frame) {
        if (frame.payload() instanceof CommandAck ack) {
            return new MatchKey(frame.header().system(), COMMAND_ACK_MESSAGE_ID, ack.command().value());
        }
        return null;
    }
}
