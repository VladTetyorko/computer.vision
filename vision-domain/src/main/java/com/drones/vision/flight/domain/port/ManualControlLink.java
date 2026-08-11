package com.drones.vision.flight.domain.port;

import com.drones.vision.warehouse.domain.model.Device;

/**
 * Opaque, adapter-owned handle to one {@link ManualControlPort#engage(Device) engaged} relay
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
}
