package com.drones.mavlink.transport;

import java.io.IOException;
import java.net.DatagramSocket;
import java.time.Duration;

/**
 * <b>Dials</b> a fixed destination from an ephemeral local UDP port — the shape a TX simulator
 * needs (send a synthetic feed at one configured address). Unlike {@link UdpListenLink}, the
 * destination never changes: {@link #defaultTarget()} always returns the configured address.
 *
 * <p>Still fully bidirectional — the ephemeral local port this opens can receive replies just like
 * any bound UDP socket, so {@link #poll} works exactly as it does on {@link UdpListenLink}, should
 * a caller want it.
 */
public final class UdpTargetLink implements MavlinkLink {

    private final LinkId id;
    private final UdpSocketIo io;
    private final LinkPeer destination;

    public UdpTargetLink(String destinationHost, int destinationPort) throws IOException {
        if (destinationHost == null || destinationHost.isBlank()) {
            throw new IllegalArgumentException("destinationHost must not be blank");
        }
        if (destinationPort < 1 || destinationPort > 65_535) {
            throw new IllegalArgumentException("destinationPort must be 1..65535, got " + destinationPort);
        }
        DatagramSocket socket = new DatagramSocket(0);
        this.io = new UdpSocketIo(socket);
        this.destination = new LinkPeer(destinationHost, destinationPort);
        this.id = new LinkId("udp-target:" + destinationHost + ":" + destinationPort);
    }

    @Override
    public LinkId id() {
        return id;
    }

    @Override
    public boolean preservesMessageBoundaries() {
        return true;
    }

    @Override
    public ByteChunk poll(Duration timeout) throws IOException {
        return io.poll(timeout);
    }

    @Override
    public void send(byte[] frame, int off, int len, LinkPeer target) throws IOException {
        io.send(frame, off, len, target);
    }

    @Override
    public LinkPeer defaultTarget() {
        return destination;
    }

    @Override
    public void close() {
        io.close();
    }
}
