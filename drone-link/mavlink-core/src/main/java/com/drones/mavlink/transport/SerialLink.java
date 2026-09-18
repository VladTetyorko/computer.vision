package com.drones.mavlink.transport;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A serial (jSerialComm-backed) {@link MavlinkLink} — modelled line-for-line on {@link
 * TcpClientLink}: {@link #preservesMessageBoundaries()} is honestly {@code false} (one {@link
 * #poll} may return less than one frame, more than one, or a frame split across several polls,
 * exactly like a TCP stream), {@code id} is stable for the port's whole life ({@code "serial:" +
 * portDescriptor}), and {@link #send}'s {@code target} parameter is accepted for interface
 * uniformity only — a serial wire has exactly one destination, the far end of the cable, which
 * {@link #defaultTarget()} honestly reports as {@link LinkPeer#NONE} (a serial link has no
 * addressable network peer at all, unlike UDP/TCP).
 *
 * <h2>Two ways to build one</h2>
 * {@link #open(String, int)} is the production path: opens a real jSerialComm port. The
 * package-private {@link #SerialLink(SerialChannel)} constructor is the test seam (java-clean-code
 * skill §3's "inject a clock or a backoff bound" allowance) — a unit test supplies a fake {@link
 * SerialChannel} so it never touches a real port, while {@code SerialLinkSocatIT} feeds a real one
 * (via a {@code socat} pty pair) through the same constructor to prove the wiring end-to-end.
 */
public final class SerialLink implements MavlinkLink {

    private final LinkId id;
    private final SerialChannel channel;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final byte[] buffer = new byte[8192];

    /** Opens {@code portDescriptor} (e.g. {@code "/dev/ttyUSB0"}) at {@code baudRate} immediately. */
    public static SerialLink open(String portDescriptor, int baudRate) throws IOException {
        return new SerialLink(JSerialCommChannel.open(portDescriptor, baudRate));
    }

    SerialLink(SerialChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.id = new LinkId("serial:" + channel.portDescriptor());
    }

    @Override
    public LinkId id() {
        return id;
    }

    @Override
    public boolean preservesMessageBoundaries() {
        return false;
    }

    /**
     * Unlike {@link TcpClientLink}, a negative read here is reported as a genuine {@link
     * IOException} rather than a silent close. A TCP {@code read() == -1} is the remote peer's own
     * orderly FIN — an application-level shutdown signal the far end chose to send. A serial file
     * descriptor reaching end-of-stream has no such peer to send one: it means the underlying
     * device disappeared out from under this link (a USB radio unplugged, a bridge process killed)
     * while nobody asked this link to close — exactly the "genuine failure" {@link
     * com.drones.mavlink.session.MavlinkSession#onLinkFailure} exists to report (FLEET-RADIO-PLAN.md
     * F7/D5, extended here to the serial carrier).
     */
    @Override
    public ByteChunk poll(Duration timeout) throws IOException {
        if (closed.get()) {
            return null;
        }
        try {
            int read = channel.read(buffer, Timeouts.clampMillis(timeout));
            if (read < 0) {
                throw new IOException(
                        "Serial channel " + channel.portDescriptor() + " reached end-of-stream (device disappeared)");
            }
            if (read == 0) {
                return null; // semi-blocking timeout, nothing available yet
            }
            return new ByteChunk(Arrays.copyOf(buffer, read), read, LinkPeer.NONE, Instant.now());
        } catch (IOException e) {
            if (closed.get()) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public void send(byte[] frame, int off, int len, LinkPeer target) throws IOException {
        channel.write(frame, off, len);
    }

    @Override
    public LinkPeer defaultTarget() {
        return LinkPeer.NONE;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            channel.close();
        }
    }
}
