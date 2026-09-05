package com.drones.mavlink.transport;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared receive/send plumbing for {@link UdpListenLink} and {@link UdpTargetLink} — the two
 * implementations differ only in how their socket is opened (bind an address for listen, an
 * ephemeral local port for target) and what {@code defaultTarget()} reports; the mechanics of
 * polling and sending over an already-bound {@link DatagramSocket} are otherwise identical.
 * Package-private: an implementation detail, not part of the frozen L1 seam.
 *
 * <p>Ports the technique in {@code adapter-mavlink}'s {@code MavlinkUdpInputStream}/
 * {@code MavlinkSocketHub}: a reused receive buffer, close-the-socket-to-unblock-a-pending-receive,
 * and "any IOException after close() was requested is that close, not a real failure."
 */
final class UdpSocketIo {

    /** Max UDP payload over IPv4 with room to spare — real MAVLink 2 frames are far smaller (&lt;300 bytes). */
    private static final int MAX_DATAGRAM_BYTES = 65_507;

    private final DatagramSocket socket;
    private final byte[] buffer = new byte[MAX_DATAGRAM_BYTES];
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Intake counters -- deliberately updated on every successful receive, pre-parse (see
    // LinkIntake's own javadoc). Atomics because intake() may be read from any thread (e.g. a
    // status endpoint) while poll() is driven exclusively by this link's one reader thread.
    private final AtomicLong datagramsReceived = new AtomicLong();
    private final AtomicLong bytesReceived = new AtomicLong();
    private final AtomicReference<Instant> lastDatagramAt = new AtomicReference<>();

    UdpSocketIo(DatagramSocket socket) {
        this.socket = socket;
    }

    /** Not thread-safe: only ever driven by the one reader thread that owns this link, per {@link MavlinkLink}'s contract. */
    ByteChunk poll(Duration timeout) throws IOException {
        if (closed.get()) {
            return null;
        }
        try {
            socket.setSoTimeout(Timeouts.clampMillis(timeout));
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
            Instant receivedAt = Instant.now();
            datagramsReceived.incrementAndGet();
            bytesReceived.addAndGet(packet.getLength());
            lastDatagramAt.set(receivedAt);
            LinkPeer source = new LinkPeer(packet.getAddress().getHostAddress(), packet.getPort());
            byte[] data = Arrays.copyOf(buffer, packet.getLength());
            return new ByteChunk(data, packet.getLength(), source, receivedAt);
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException e) {
            if (closed.get()) {
                return null; // close() unblocked us -- not a real failure, see MavlinkLink#poll's contract
            }
            throw e;
        }
    }

    /** Safe to call from any thread. Counted pre-parse, at the socket -- see {@link LinkIntake}. */
    LinkIntake intake() {
        return new LinkIntake(datagramsReceived.get(), bytesReceived.get(), lastDatagramAt.get());
    }

    /** {@code DatagramSocket#send} is already thread-safe, so this needs no extra locking. */
    void send(byte[] frame, int off, int len, LinkPeer target) throws IOException {
        LinkPeers.requireAddressable(target);
        socket.send(new DatagramPacket(frame, off, len, InetAddress.getByName(target.host()), target.port()));
    }

    void close() {
        if (closed.compareAndSet(false, true) && !socket.isClosed()) {
            socket.close();
        }
    }
}
