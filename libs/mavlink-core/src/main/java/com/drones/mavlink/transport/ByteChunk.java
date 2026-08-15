package com.drones.mavlink.transport;

import java.time.Instant;
import java.util.Objects;

/**
 * A slice of bytes read off a {@link MavlinkLink} in one {@link MavlinkLink#poll} call.
 *
 * <p>{@code data} may be longer than {@code length} — an implementation is free to reuse a
 * backing buffer across polls rather than allocate exactly-sized arrays; callers must only ever
 * consider {@code data[0, length)} valid. {@code source} is the sender for a datagram-shaped link,
 * or the link's one connected peer for a stream-shaped link.
 */
public record ByteChunk(byte[] data, int length, LinkPeer source, Instant receivedAt) {

    public ByteChunk {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (length < 0 || length > data.length) {
            throw new IllegalArgumentException(
                    "length " + length + " out of bounds for data of size " + data.length);
        }
    }
}
