package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for a transmitted feed (see {@code com.drones.vision.domain.port.out.FeedTransmitterPort}).
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so feed identities cannot be mixed up with stream, device,
 * or other typed identifiers at compile time, and can never hold a malformed
 * value — the same rationale as {@link StreamId}/{@link DeviceId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record FeedId(UUID value) {

    public FeedId {
        if (value == null) {
            throw new IllegalArgumentException("FeedId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code FeedId} using a random UUID.
     *
     * @return a fresh {@code FeedId}
     */
    public static FeedId random() {
        return new FeedId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code FeedId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code FeedId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static FeedId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("FeedId value must not be null");
        }
        try {
            return new FeedId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("FeedId value must be a valid UUID: " + value, e);
        }
    }
}
