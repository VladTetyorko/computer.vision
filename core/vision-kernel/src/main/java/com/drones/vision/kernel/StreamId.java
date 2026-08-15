package com.drones.vision.kernel;

import java.util.UUID;

/**
 * Typed identity for a running stream pipeline.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so stream identities cannot be mixed up with device,
 * model, or other typed identifiers at compile time, and can never hold a
 * malformed value. A stream pipeline is pinned to a single JVM instance, and
 * horizontal scaling means distributing streams across instances — {@code
 * StreamId} is therefore a future sharding/partition key, which is exactly
 * why it is a distinct type rather than a {@code String} today.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record StreamId(UUID value) {

    public StreamId {
        if (value == null) {
            throw new IllegalArgumentException("StreamId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code StreamId} using a random UUID.
     *
     * @return a fresh {@code StreamId}
     */
    public static StreamId random() {
        return new StreamId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code StreamId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code StreamId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static StreamId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("StreamId value must not be null");
        }
        try {
            return new StreamId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("StreamId value must be a valid UUID: " + value, e);
        }
    }
}
