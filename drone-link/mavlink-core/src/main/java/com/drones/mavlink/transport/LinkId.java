package com.drones.mavlink.transport;

import java.util.Objects;

/**
 * The stable identity of a {@link MavlinkLink} for its whole life, e.g.
 * {@code "udp-listen:0.0.0.0:14550"}. Used as a map key wherever an upper layer needs to name a
 * specific link (routing, per-link resync state, per-link sequence counters) without holding a
 * reference to the link object itself.
 */
public record LinkId(String value) {

    public LinkId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("LinkId value must not be blank");
        }
    }
}
