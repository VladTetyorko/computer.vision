package com.drones.mavlink.transport;

import java.util.Objects;

/** Shared {@code send()} target validation for every {@link MavlinkLink} implementation. */
final class LinkPeers {

    private LinkPeers() {
    }

    /**
     * @throws IllegalArgumentException if {@code target} has no addressable peer — a blank host or
     *                                   a port of 0, which is exactly what {@link LinkPeer#NONE}
     *                                   is, so this rejects it without needing a special case.
     */
    static void requireAddressable(LinkPeer target) {
        Objects.requireNonNull(target, "target");
        if (target.host().isBlank() || target.port() < 1) {
            throw new IllegalArgumentException("target has no addressable peer: " + target);
        }
    }
}
