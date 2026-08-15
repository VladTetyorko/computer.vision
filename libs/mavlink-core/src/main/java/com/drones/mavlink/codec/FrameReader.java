package com.drones.mavlink.codec;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;

import io.dronefleet.mavlink.Mavlink2Message;
import io.dronefleet.mavlink.MavlinkMessage;
import io.dronefleet.mavlink.annotations.MavlinkMessageInfo;

import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Bytes in, frames out. Delegates all real parsing (CRC, resync-past-garbage, dialect-specific
 * field layout) to {@code io.dronefleet.mavlink} — this class's only job is bridging the
 * incremental {@link ByteChunk}s a {@link MavlinkLink} produces into that library's frame decoder,
 * one independent {@link ResyncBuffer} per source.
 *
 * <h2>One resync buffer per source</h2>
 * For a boundary-preserving link ({@link MavlinkLink#preservesMessageBoundaries()} {@code true} —
 * UDP), each distinct {@link LinkPeer} gets its own buffer. For a stream link ({@code false} — TCP)
 * every chunk shares exactly one buffer regardless of what {@link ByteChunk#source()} reports,
 * since a stream link has exactly one logical byte sequence by construction. This is not an
 * optimisation: concatenating datagrams from different vehicles into one byte stream is the latent
 * flaw in {@code adapter-mavlink}'s pre-existing {@code MavlinkUdpInputStream}, which is exactly
 * what forced source-address demux to be descoped there before. Buffers are bounded and evicted
 * least-recently-used at {@link MavlinkCoreSettings#maxResyncBuffers()} so a spoofing or scanning
 * source cannot grow memory without bound.
 *
 * <p>Not thread-safe: intended to be driven by exactly one reader thread per link (matching L3's
 * {@code MavlinkSession}, which owns one reader thread per link), the same "one thread owns the
 * read side" convention every link implementation in {@code com.drones.mavlink.transport} follows.
 */
public final class FrameReader {

    private static final System.Logger LOG = System.getLogger(FrameReader.class.getName());

    /** Sentinel key for the single shared buffer a stream link's chunks all fall into. */
    private static final LinkPeer STREAM_KEY = new LinkPeer("<stream>", 1);

    private final LinkId link;
    private final boolean preservesMessageBoundaries;
    private final int maxResyncBuffers;
    /**
     * Learned dialect per MAVLink system id, shared by every {@link ResyncBuffer} on this link — a
     * vehicle's dialect belongs to the vehicle, not to the address its datagrams arrive from. See
     * {@code ResyncBuffer#dialectForPendingFrame} for the full reasoning.
     */
    private final Map<Integer, io.dronefleet.mavlink.MavlinkDialect> dialectsBySystem = new ConcurrentHashMap<>();
    private final Map<LinkPeer, ResyncBuffer> buffers;

    public FrameReader(MavlinkLink link, MavlinkCoreSettings settings) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(settings, "settings");
        this.link = link.id();
        this.preservesMessageBoundaries = link.preservesMessageBoundaries();
        this.maxResyncBuffers = settings.maxResyncBuffers();
        this.buffers = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<LinkPeer, ResyncBuffer> eldest) {
                return size() > FrameReader.this.maxResyncBuffers;
            }
        };
    }

    public FrameReader(MavlinkLink link) {
        this(link, MavlinkCoreSettings.defaults());
    }

    /**
     * Feeds {@code chunk}'s bytes into that source's resync buffer and hands every frame that
     * becomes fully decodable as a result to {@code out}, in order. May decode zero, one, or
     * several frames from a single chunk; never blocks.
     */
    public void offer(ByteChunk chunk, Consumer<MavFrame> out) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(out, "out");
        LinkPeer key = preservesMessageBoundaries ? chunk.source() : STREAM_KEY;
        ResyncBuffer buffer = buffers.computeIfAbsent(key, k -> new ResyncBuffer(dialectsBySystem));
        buffer.append(chunk.data(), chunk.length());
        buffer.drain(message -> emit(message, chunk.source(), chunk.receivedAt(), out));
    }

    private void emit(MavlinkMessage<?> message, LinkPeer source, Instant receivedAt, Consumer<MavFrame> out) {
        try {
            out.accept(toFrame(message, source, receivedAt));
        } catch (IllegalArgumentException e) {
            // A malformed-but-CRC-valid origin (e.g. sysid/compid 0) -- skip this one frame rather
            // than let a single bad frame stall every other source's decoding.
            LOG.log(Level.WARNING, "Discarding a decoded MAVLink frame with an invalid origin identity", e);
        }
    }

    private MavFrame toFrame(MavlinkMessage<?> message, LinkPeer source, Instant receivedAt) {
        boolean isV2 = message instanceof Mavlink2Message<?>;
        int version = isV2 ? 2 : 1;
        byte[] raw = message.getRawBytes();
        int incompatFlags = isV2 ? (raw[2] & 0xFF) : 0;
        int compatFlags = isV2 ? (raw[3] & 0xFF) : 0;
        boolean signed = isV2 && ((Mavlink2Message<?>) message).isSigned();
        MavHeader header = new MavHeader(
                version,
                message.getSequence(),
                new SysId(message.getOriginSystemId()),
                new CompId(message.getOriginComponentId()),
                messageIdOf(message.getPayload()),
                incompatFlags,
                compatFlags,
                signed);
        return new MavFrame(header, message.getPayload(), link, source, receivedAt);
    }

    private static int messageIdOf(Object payload) {
        MavlinkMessageInfo info = payload.getClass().getAnnotation(MavlinkMessageInfo.class);
        if (info == null) {
            // Should be impossible: the library only ever resolves a payload class via a dialect's
            // own registry, and every registered class carries this annotation.
            throw new IllegalStateException(
                    "Decoded MAVLink payload " + payload.getClass() + " has no @MavlinkMessageInfo");
        }
        return info.id();
    }
}
