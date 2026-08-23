package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.VehicleKind;

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
     * arrives (docs/plans/done/RC-LATENCY-PLAN.md §2 C).
     *
     * <p>Exists so a caller can report the <em>real</em> rate rather than mirror a constant. The
     * value is resolved at {@link ManualControlPort#engage engage} time and does not change for the
     * life of the link.
     *
     * @return a positive whole-Hz cadence
     */
    int rateHz();

    /**
     * What kind of machine this link is engaged to, as most recently reported by the vehicle itself
     * — the fact {@link com.drones.vision.flight.domain.model.ControlProfile#forKind} needs to decide
     * where a throttle rests (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P9).
     *
     * <p>Rides the link for the same reason {@link #rateHz()} does: only the adapter can answer it —
     * the classification comes from a protocol field this module may not name (MAVLink's {@code
     * HEARTBEAT.type}) — and the answer belongs to one engaged link, resolved at {@link
     * ManualControlPort#engage engage} time from what is being heard right then. An implementation
     * that cannot classify its vehicle returns {@link VehicleKind#UNKNOWN}; it must never guess.
     *
     * @return the vehicle kind, never {@code null}
     */
    VehicleKind vehicleKind();
}
