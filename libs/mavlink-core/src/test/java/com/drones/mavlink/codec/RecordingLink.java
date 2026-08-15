package com.drones.mavlink.codec;

import com.drones.mavlink.transport.ByteChunk;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;
import com.drones.mavlink.transport.MavlinkLink;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An in-memory {@link MavlinkLink} test double: records every {@link #send} into {@link #sent()}
 * instead of touching a real socket, so codec tests can drive {@code FrameWriter}/{@code
 * FrameReader} deterministically without any I/O. {@link #poll} is unused by every codec test
 * (they feed bytes to {@code FrameReader} directly via manually built {@link ByteChunk}s) and
 * throws if ever called.
 */
final class RecordingLink implements MavlinkLink {

    private final LinkId id;
    private final boolean preservesMessageBoundaries;
    private final LinkPeer defaultTarget;
    private final List<byte[]> sent = new ArrayList<>();

    RecordingLink(String id, boolean preservesMessageBoundaries, LinkPeer defaultTarget) {
        this.id = new LinkId(id);
        this.preservesMessageBoundaries = preservesMessageBoundaries;
        this.defaultTarget = defaultTarget;
    }

    List<byte[]> sent() {
        return sent;
    }

    @Override
    public LinkId id() {
        return id;
    }

    @Override
    public boolean preservesMessageBoundaries() {
        return preservesMessageBoundaries;
    }

    @Override
    public ByteChunk poll(Duration timeout) {
        throw new UnsupportedOperationException("RecordingLink is send-only in tests");
    }

    @Override
    public void send(byte[] frame, int off, int len, LinkPeer target) {
        sent.add(Arrays.copyOfRange(frame, off, off + len));
    }

    @Override
    public LinkPeer defaultTarget() {
        return defaultTarget;
    }

    @Override
    public void close() {
        // no real resource to release
    }
}
