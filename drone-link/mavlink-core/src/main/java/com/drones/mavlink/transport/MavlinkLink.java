package com.drones.mavlink.transport;

import java.io.IOException;
import java.time.Duration;

/**
 * Bytes in, bytes out. A {@code MavlinkLink} knows nothing about MAVLink frames — that is
 * {@code com.drones.mavlink.codec}'s job — only how to move raw bytes over one physical or logical
 * connection (UDP socket, TCP connection, eventually serial).
 *
 * <h2>The LSP hinge: {@link #preservesMessageBoundaries()}</h2>
 * This interface's real contract is <i>"a byte stream with buffered resync,"</i> which a datagram
 * link satisfies trivially (one {@link #poll} happens to return exactly one datagram's worth of
 * bytes) and a stream link satisfies correctly (one {@link #poll} may return less than one frame,
 * more than one frame, or a frame split across several polls). {@code preservesMessageBoundaries()}
 * must <b>never</b> be read as "one poll = one message" — that promise would make
 * {@link TcpClientLink} an unimplementable subtype of this interface. The flag exists only so the
 * codec layer's {@code FrameReader} can decide how many resync buffers to keep (one per learned
 * peer for a boundary-preserving link, exactly one for a stream link), never so a caller can skip
 * resync.
 *
 * <h2>Threading</h2>
 * Implementations are driven by exactly one reader thread calling {@link #poll} in a loop — that
 * thread owns the read side. {@link #send} is required to be thread-safe on every implementation
 * because several services commonly share one link concurrently (a telemetry reader, a command
 * sender and a manual-control relay all writing to the same socket).
 */
public interface MavlinkLink extends AutoCloseable {

    /** This link's stable identity for its whole life. */
    LinkId id();

    /**
     * {@code true} for a datagram-shaped link (UDP: one underlying receive == one poll's worth of
     * bytes), {@code false} for a stream-shaped link (TCP, eventually serial). See the class
     * javadoc — this is a hint for the codec layer's resync bookkeeping, not a promise about frame
     * boundaries.
     */
    boolean preservesMessageBoundaries();

    /**
     * Blocks up to {@code timeout} for the next slice of bytes.
     *
     * @return the next {@link ByteChunk}, or {@code null} on timeout <b>or</b> because the link is
     *         closed — a closed link never throws for that reason, named after
     *         {@link java.util.concurrent.BlockingQueue#poll} whose return value carries the same
     *         "null means nothing available" contract. Only a genuine I/O failure throws.
     * @throws IOException on a real socket/stream failure, not on timeout or close
     */
    ByteChunk poll(Duration timeout) throws IOException;

    /**
     * Sends {@code len} bytes starting at {@code off} in {@code frame} to {@code target}.
     * Thread-safe — see the class javadoc.
     *
     * @throws IllegalArgumentException if {@code target} has no addressable peer (e.g.
     *                                   {@link LinkPeer#NONE}, or an unresolved host/port)
     * @throws IOException              on a genuine send failure
     */
    void send(byte[] frame, int off, int len, LinkPeer target) throws IOException;

    /**
     * This link's natural target when the caller has no more specific address in hand: the
     * configured fixed destination for a dial-out link, the one connected remote for a stream
     * link, or the most recently learned sender for a listen link ({@link LinkPeer#NONE} before
     * anything has been heard).
     */
    LinkPeer defaultTarget();

    /**
     * Closes this link. Idempotent. Unblocks any thread currently parked in {@link #poll} by
     * closing the underlying socket/stream out from under it, per this module's
     * close-to-unblock-the-reader idiom — that thread's {@link #poll} then returns {@code null}
     * rather than throwing. Declared to never throw (narrower than {@link AutoCloseable#close()},
     * which every implementation here honours) so try-with-resources works without a checked
     * exception forcing every caller to handle one.
     */
    @Override
    void close();
}
