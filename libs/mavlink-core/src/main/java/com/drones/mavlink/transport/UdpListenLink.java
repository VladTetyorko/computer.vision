package com.drones.mavlink.transport;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * <b>Listens</b> — binds a local UDP address and accepts datagrams from whoever sends to it. This
 * is how a telemetry radio or a SITL instance <i>pushes</i> MAVLink at this platform: the URI
 * names the local bind address, not a remote one to dial.
 *
 * <p>{@link #defaultTarget()} returns the most recently learned sender address (the peer of the
 * last successfully polled {@link ByteChunk}), {@link LinkPeer#NONE} before anything has been
 * heard. This is a convenience default for "reply to whoever we most recently heard from," not a
 * routing table — real per-peer address tracking is an L3 ({@code PeerDirectory}) concern.
 */
public final class UdpListenLink implements MavlinkLink {

    private final LinkId id;
    private final UdpSocketIo io;
    private volatile LinkPeer lastLearnedPeer = LinkPeer.NONE;

    /**
     * Binds immediately. {@code bindHost} blank/{@code "0.0.0.0"} binds every interface;
     * {@code bindPort} {@code 0} lets the OS pick an ephemeral port (see {@link #id()}, which
     * reflects the port actually bound, not the requested one).
     */
    public UdpListenLink(String bindHost, int bindPort) throws IOException {
        DatagramSocket socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(bindHost, bindPort));
        this.io = new UdpSocketIo(socket);
        this.id = new LinkId("udp-listen:" + bindHost + ":" + socket.getLocalPort());
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
        ByteChunk chunk = io.poll(timeout);
        if (chunk != null) {
            lastLearnedPeer = chunk.source();
        }
        return chunk;
    }

    @Override
    public void send(byte[] frame, int off, int len, LinkPeer target) throws IOException {
        io.send(frame, off, len, target);
    }

    @Override
    public LinkPeer defaultTarget() {
        return lastLearnedPeer;
    }

    @Override
    public void close() {
        io.close();
    }
}
