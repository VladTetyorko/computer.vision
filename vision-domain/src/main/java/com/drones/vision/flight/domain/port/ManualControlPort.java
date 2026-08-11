package com.drones.vision.flight.domain.port;

import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.flight.domain.model.RcChannels;

/**
 * Driven port: a streaming, ack-less RC-channel-override relay (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md
 * §1) — the fire-and-forget, continuous-stream opposite of {@link FlightCommandPort}'s
 * request→ack one-shots. An implementation owns a fixed-rate sender thread per {@link
 * #engage(Device) engaged} link; the caller only ever hands it the <em>latest</em> channel frame,
 * decoupling the caller's own send rate from the wire's fixed cadence.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports(Device)} must be checked (or {@link #engage(Device)} allowed to throw)
 *       before any other method — not every device this platform ingests telemetry from is
 *       manually-drivable by a given implementation.</li>
 *   <li>{@link #engage(Device)} opens a link and starts its sender thread; {@link #send} hands
 *       that thread the newest channels via a single-slot, latest-wins mailbox; {@link #release}
 *       emits a short release-sentinel burst then stops the thread. Every method is a no-op on an
 *       already-released link.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * {@link #supports(Device)} and {@link #engage(Device)} must be safe to call concurrently for
 * different devices. {@link #send} must be non-blocking and must never itself write to the wire —
 * the link's own sender thread owns that. {@link #release} may block briefly to join that thread.
 */
public interface ManualControlPort {

    /**
     * Whether this adapter can drive the aircraft behind the given device with RC channel
     * override.
     *
     * @param device the device to check
     * @return {@code true} if this adapter can attempt {@link #engage(Device)} for it
     */
    boolean supports(Device device);

    /**
     * Opens a relay link to the aircraft behind {@code device} and starts its fixed-rate sender
     * thread.
     *
     * @param device the device to engage
     * @return the opened, adapter-owned link
     * @throws IllegalArgumentException if {@code device} is unsupported, or it is supported but
     *                                   not currently reachable — "you cannot command what you
     *                                   cannot hear": no live source address has been heard from
     *                                   it yet
     */
    ManualControlLink engage(Device device);

    /**
     * Hands the sender thread the newest channel frame via a single-slot, latest-wins mailbox: a
     * frame that arrives before the previous one reached the wire simply overwrites it.
     * Non-blocking — implementations must never write to the wire from this call, only the link's
     * own sender thread may do that. A no-op once {@code link} has been {@link #release released}.
     *
     * @param link     the link to send on, from {@link #engage(Device)}
     * @param channels the newest channel frame
     */
    void send(ManualControlLink link, RcChannels channels);

    /**
     * Releases {@code link}: sends a short burst of release-sentinel frames ({@link
     * RcChannels#released(int)}) so the aircraft's own failsafe takes over, then stops the sender
     * thread. Idempotent — releasing an already-released link is a no-op.
     *
     * @param link the link to release
     */
    void release(ManualControlLink link);
}
