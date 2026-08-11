package com.drones.vision.adapter.mavlink;

import io.dronefleet.mavlink.common.CommandAck;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One {@link MavlinkSocketHub}'s pending {@code COMMAND_ACK} waiters (docs/plans/active/DRONE-INFRA-PLAN.md I-e
 * Stage 1, split out of {@code MavlinkSocketHub} itself per docs/plans/active/LAYERING-REFACTOR-PLAN.md E2) —
 * the narrow seam a command sender ({@code MavlinkFlightCommander}, {@code
 * MavlinkManualControlSender}) uses to await the matching reply on the hub's own read thread
 * instead of opening a second socket reader.
 *
 * <h2>Threading</h2>
 * Guarded by its own private monitor, independent of {@link VehicleClaimRegistry}'s lock: ack
 * matching and claim resolution are unrelated concerns that never need to be atomic with each
 * other (see {@link MavlinkSocketHub#routeMessage}, which now touches each registry's lock
 * separately instead of one combined critical section).
 *
 * <p>Package-private, owned entirely by {@link MavlinkSocketHub} — not a domain/port type.
 */
final class CommandAckRegistry {

    private final Object lock = new Object();
    private final Map<AckKey, CompletableFuture<CommandAck>> waiters = new HashMap<>();

    /**
     * Registers interest in the next {@code COMMAND_ACK} carrying {@code commandId} from {@code
     * sysid}. The returned future completes on the hub's read thread the instant a matching ack is
     * routed; it is never completed at all if none ever arrives, so the caller must apply its own
     * timeout and always pair this with {@link #cancel} in a {@code finally} block.
     */
    CompletableFuture<CommandAck> await(int sysid, int commandId) {
        CompletableFuture<CommandAck> future = new CompletableFuture<>();
        synchronized (lock) {
            waiters.put(new AckKey(sysid, commandId), future);
        }
        return future;
    }

    /** Releases a waiter registered via {@link #await}. Idempotent. */
    void cancel(int sysid, int commandId) {
        synchronized (lock) {
            waiters.remove(new AckKey(sysid, commandId));
        }
    }

    /**
     * Removes and returns the waiter matching {@code ack}'s {@code (sysid, command id)}, if any.
     * The caller completes the returned future itself, outside any lock (mirrors the original
     * {@code MavlinkSocketHub#routeMessage} discipline of never completing a future while holding
     * the hub's monitor).
     */
    CompletableFuture<CommandAck> claimWaiter(int sysid, CommandAck ack) {
        synchronized (lock) {
            return waiters.remove(new AckKey(sysid, ack.command().value()));
        }
    }

    /** Key a pending {@link #await} waiter is registered/matched under: which vehicle, which command. */
    private record AckKey(int sysid, int commandId) {
    }
}
