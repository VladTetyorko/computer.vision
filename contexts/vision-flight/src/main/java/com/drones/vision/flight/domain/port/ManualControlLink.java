package com.drones.vision.flight.domain.port;

/**
 * Opaque, adapter-owned handle to one
 * {@link ManualControlPort#engage(com.drones.vision.warehouse.domain.model.Device) engaged} relay
 * link. The domain declares the shape; whatever {@link ManualControlPort#engage} returns decides
 * what it actually holds (socket, resolved target address, sender thread, ...) — the domain never
 * looks inside it.
 */
public interface ManualControlLink {

    /**
     * Whether this link's sender thread is still running.
     *
     * @return {@code true} while the link is engaged and its sender thread is live; {@code false}
     *         once {@link ManualControlPort#release(ManualControlLink)} has completed (or the link
     *         failed on its own)
     */
    boolean active();

    /**
     * The cadence, in whole Hz, at which this link's implementation guarantees to keep transmitting
     * — its keepalive floor, not a ceiling: an implementation may transmit sooner when new input
     * arrives (docs/plans/active/RC-LATENCY-PLAN.md §2 C).
     *
     * <p>Exists so a caller can report the <em>real</em> rate rather than mirror a constant. The
     * value is resolved at {@link ManualControlPort#engage engage} time and does not change for the
     * life of the link.
     *
     * @return a positive whole-Hz cadence
     */
    int rateHz();
}
