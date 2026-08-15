package com.drones.mavlink.codec;

import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.LinkPeer;

import java.time.Instant;
import java.util.Objects;

/**
 * One fully decoded MAVLink frame: header plus payload, stamped with where it came from.
 *
 * <p>{@code payload} is typed {@code Object} — a known wart, accepted under plan D3 (the
 * underlying library's ~500 generated message classes flow through this layer as-is rather than
 * being re-modelled as this module's own DTOs). {@link #is} / {@link #as} exist so callers rarely
 * need a raw {@code instanceof}/cast pair at every use site.
 */
public record MavFrame(MavHeader header, Object payload, LinkId link, LinkPeer source, Instant receivedAt) {

    public MavFrame {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(receivedAt, "receivedAt");
    }

    /** {@code true} if {@code payload} is an instance of {@code type}. */
    public boolean is(Class<?> type) {
        Objects.requireNonNull(type, "type");
        return type.isInstance(payload);
    }

    /**
     * @throws ClassCastException if {@code payload} is not an instance of {@code type} — check
     *                             with {@link #is} first when the type is not already known
     */
    public <T> T as(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.cast(payload);
    }
}
