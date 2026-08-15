package com.drones.mavlink.transport;

import java.util.Objects;

/**
 * A physical address on a link — the host/port a datagram was received from or should be sent to.
 * This is deliberately <b>not</b> a MAVLink identity: {@link com.drones.mavlink.PeerId} is who a
 * message is from ({@code sysid}/{@code compid}); {@code LinkPeer} is where the bytes travel on
 * the wire. The distinction matters because one physical address can carry several MAVLink
 * components, and NAT can move the observed address between packets from the very same system.
 *
 * @param host non-null; may be blank only for {@link #NONE}
 * @param port 0..65535; {@code 0} means "no real port" and is only meaningful for {@link #NONE}
 */
public record LinkPeer(String host, int port) {

    /** Sentinel for a link with no addressable peer (e.g. a future serial link). */
    public static final LinkPeer NONE = new LinkPeer("", 0);

    public LinkPeer {
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("port must be 0..65535, got " + port);
        }
    }
}
