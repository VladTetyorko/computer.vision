package com.drones.mavlink.transport;

import java.util.Objects;

/**
 * Metadata a carrier adapter attaches to a {@link MavlinkLink} when it {@link
 * LinkRegistry#register registers} it — everything an upper layer (L3 election, a future {@code
 * CarriersController}) needs to reason about a link without inspecting the link object itself.
 *
 * @param carrier    which technology this link rides on
 * @param serialRole {@link SerialRole#NONE} for every {@link CarrierKind#UDP} descriptor — never
 *                   {@code null}, see that enum's own javadoc
 * @param label      a short, human-facing name (e.g. {@code "lobby"}, a serial port's descriptive
 *                   name) — never blank
 * @param priority   plain registration-time int an election layer orders links by; higher wins.
 *                   Not itself a guarantee of auto-election — {@link SerialRole#BENCH} links
 *                   register at {@code 0} and are never auto-elected regardless of this number
 *                   (LINK-PAIRING-PLAN.md §3.2)
 */
public record LinkDescriptor(CarrierKind carrier, SerialRole serialRole, String label, int priority) {

    public LinkDescriptor {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(serialRole, "serialRole");
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must be >= 0, got " + priority);
        }
    }
}
