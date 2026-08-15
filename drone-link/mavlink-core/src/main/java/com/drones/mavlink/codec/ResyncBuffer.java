package com.drones.mavlink.codec;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.MavlinkDialect;
import io.dronefleet.mavlink.MavlinkMessage;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/**
 * One source's accumulated-but-not-yet-fully-decoded bytes, plus enough state to hand each
 * complete frame to {@code io.dronefleet.mavlink.MavlinkConnection} for real decoding without
 * re-implementing any of CRC validation, resync, or dialect-specific field layout ourselves.
 *
 * <h2>Why a fresh {@code MavlinkConnection} per parse attempt</h2>
 * {@code MavlinkConnection#next()} is built around a genuinely <b>blocking</b>
 * {@link InputStream}: it never expects a byte-not-available condition mid-frame other than true,
 * permanent end of stream. This module's {@code FrameReader#offer} is the opposite shape —
 * incremental bytes pushed in, decoded frames must come out synchronously, and there is no
 * dedicated thread per source to block on a real read. Reusing one persistent
 * {@code MavlinkConnection}/{@code MavlinkPacketReader} across calls and simply feeding it more
 * bytes later does not work: its internal {@code TransactionalInputStream} records every byte it
 * reads for possible rollback, and an aborted read (because our bytes ran out) leaves that
 * bookkeeping in a state a later call cannot safely resume — a retried {@code next()} would
 * misread the middle of an unfinished frame as a fresh version-marker search.
 *
 * <p>So every parse attempt instead gets its own {@link MavlinkConnection} wrapping a
 * {@link ByteArrayInputStream} over the <em>entire</em> currently pending buffer, started fresh
 * from position 0. A {@link CountingInputStream} underneath it records exactly how many bytes were
 * consumed (including any garbage the library's own resync skipped past) so the pending buffer can
 * be trimmed correctly regardless of whether the attempt succeeded. On failure (ran out of bytes
 * before completing a frame — reported as {@link EOFException}, a completely normal outcome here,
 * not a real error) nothing is trimmed; the whole buffer, garbage prefix included, is retried once
 * more bytes arrive. Frame payloads are at most 280 bytes and a realistic garbage prefix is a
 * handful of bytes at most, so re-scanning it on every offer is not a real cost.
 *
 * <h2>Dialect persistence across attempts</h2>
 * Losing the persistent connection also loses its per-system dialect cache — the mechanism that
 * lets an ArduPilot vehicle's own {@code HEARTBEAT} unlock ardupilotmega-only messages for
 * everything decoded afterward (see {@code adapter-mavlink}'s own documented gotcha on this). This
 * class replicates just enough of it: after any successful decode, it asks that attempt's
 * (about to be discarded) connection which {@link MavlinkDialect} it resolved for the origin
 * system via {@link MavlinkConnection#getDialect(int)} — the library's own resolution, not a
 * hand-rolled autopilot-to-dialect table — and records it in a map <b>keyed by system id and shared
 * across every buffer on the link</b>, then primes the next attempt from that map. Keying it per
 * buffer instead (W1's first shape) was a real defect, not an approximation: it tied a vehicle's
 * dialect to the source address its datagrams happened to arrive from. See
 * {@link #dialectForPendingFrame()} for the full reasoning and the failure it caused.
 *
 * <p>Bounded against a link that never produces a valid frame: once the pending buffer exceeds
 * {@link #MAX_PENDING_BYTES} without resolving into a frame, it is dropped and a warning logged —
 * otherwise a noisy or adversarial source could grow it without bound.
 *
 * <p>Not thread-safe — see {@link FrameReader}'s own javadoc for the "one reader thread per link"
 * assumption this shares.
 */
final class ResyncBuffer {

    /** Generous headroom over MAVLink v2's 280-byte frame ceiling; bounds a stuck buffer's memory. */
    private static final int MAX_PENDING_BYTES = 4_096;

    private static final System.Logger LOG = System.getLogger(ResyncBuffer.class.getName());

    /** MAVLink 2 frame-start marker; system id sits at offset 5. */
    private static final int MAGIC_V2 = 0xFD;
    /** MAVLink 1 frame-start marker; system id sits at offset 3. */
    private static final int MAGIC_V1 = 0xFE;
    private static final int SYSID_OFFSET_V2 = 5;
    private static final int SYSID_OFFSET_V1 = 3;

    private final Map<Integer, MavlinkDialect> dialectsBySystem;

    private byte[] pending = new byte[0];
    private volatile MavlinkDialect lastResolvedDialect;

    ResyncBuffer(Map<Integer, MavlinkDialect> dialectsBySystem) {
        this.dialectsBySystem = dialectsBySystem;
    }

    void append(byte[] data, int length) {
        byte[] grown = Arrays.copyOf(pending, pending.length + length);
        System.arraycopy(data, 0, grown, pending.length, length);
        pending = grown;
    }

    /**
     * Decodes and emits every complete frame currently extractable from the pending buffer, then
     * applies {@link #MAX_PENDING_BYTES}. The bound is checked <b>after</b> draining, not before —
     * a single chunk containing many back-to-back valid frames (e.g. a big TCP read, or this
     * module's own sequence-wrap test feeding hundreds of frames in one offer) must be fully
     * decoded rather than discarded just because its raw length briefly exceeded the cap; only a
     * buffer that is <em>still</em> oversized once draining has made all the progress it can is
     * genuinely stuck (noise that will never resolve into a frame) and gets dropped.
     */
    void drain(Consumer<MavlinkMessage<?>> out) {
        while (pending.length > 0) {
            ParseAttempt attempt = tryParseOne();
            if (attempt == null) {
                break; // not enough data yet for a complete frame -- wait for the next offer()
            }
            pending = Arrays.copyOfRange(pending, attempt.consumedBytes, pending.length);
            if (attempt.resolvedDialect != null) {
                lastResolvedDialect = attempt.resolvedDialect;
                dialectsBySystem.put(attempt.message.getOriginSystemId(), attempt.resolvedDialect);
            }
            out.accept(attempt.message);
        }
        if (pending.length > MAX_PENDING_BYTES) {
            LOG.log(Level.WARNING,
                    "Resync buffer exceeded {0} bytes without completing a frame; discarding buffered bytes",
                    MAX_PENDING_BYTES);
            pending = new byte[0];
        }
    }

    /**
     * The dialect to decode the next pending frame with, resolved <b>by system id</b> rather than by
     * this buffer's own history.
     *
     * <p>Dialect is a property of the vehicle, not of the socket address its datagrams happen to
     * arrive from — the same identity rule the rest of this module follows ({@code (sysid, compid)},
     * never the transport peer). Keying it per resync buffer made an ArduPilot vehicle's own
     * {@code HEARTBEAT} unlock ardupilotmega-only messages for that <em>source address</em> only, so
     * the identical vehicle relayed through a second address (a companion computer, a router, or
     * simply a NAT rebind) silently fell back to {@code CommonDialect} and could no longer resolve
     * {@code WIND}/{@code EKF_STATUS_REPORT}/{@code RANGEFINDER}. The map is shared across every
     * buffer on the link, which restores the behaviour the pre-W4 adapter got for free from a single
     * long-lived {@code MavlinkConnection} and its own per-system dialect cache.
     *
     * <p>Peeking the system id costs two header reads and re-implements nothing: the frame-start
     * marker and the fixed system-id offset are the only fields consulted, never CRC, field order or
     * truncation. A peek that lands inside garbage simply primes the wrong dialect for one attempt —
     * the library's own resync then skips that noise and the next attempt peeks again.
     */
    private MavlinkDialect dialectForPendingFrame() {
        Integer system = peekSystemId();
        MavlinkDialect bySystem = system == null ? null : dialectsBySystem.get(system);
        return bySystem != null ? bySystem : lastResolvedDialect;
    }

    /** System id of the first frame-start marker in the pending buffer, or {@code null} if none is readable yet. */
    private Integer peekSystemId() {
        for (int i = 0; i < pending.length; i++) {
            int magic = pending[i] & 0xFF;
            int offset = magic == MAGIC_V2 ? SYSID_OFFSET_V2 : magic == MAGIC_V1 ? SYSID_OFFSET_V1 : -1;
            if (offset < 0) {
                continue;
            }
            int sysidIndex = i + offset;
            return sysidIndex < pending.length ? pending[sysidIndex] & 0xFF : null;
        }
        return null;
    }

    private ParseAttempt tryParseOne() {
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(pending));
        MavlinkConnection.Builder builder = MavlinkConnection.builder(counting, OutputStream.nullOutputStream());
        MavlinkDialect primed = dialectForPendingFrame();
        if (primed != null) {
            builder.defaultDialect(primed);
        }
        MavlinkConnection connection = builder.build();
        try {
            MavlinkMessage<?> message = connection.next();
            MavlinkDialect resolved = connection.getDialect(message.getOriginSystemId());
            return new ParseAttempt(message, counting.count(), resolved != null ? resolved : primed);
        } catch (EOFException e) {
            return null; // ran out of buffered bytes before completing a frame
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Unexpected failure decoding a buffered MAVLink frame; will retry with more data", e);
            return null;
        }
    }

    private record ParseAttempt(MavlinkMessage<?> message, int consumedBytes, MavlinkDialect resolvedDialect) {
    }

    /** Counts bytes actually drawn from the wrapped stream, net of anything later pushed back via pushback. */
    private static final class CountingInputStream extends FilterInputStream {

        private int count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        int count() {
            return count;
        }
    }
}
