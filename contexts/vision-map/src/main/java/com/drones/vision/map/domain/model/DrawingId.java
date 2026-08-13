package com.drones.vision.map.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link Drawing}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so drawing
 * identities cannot be mixed up with mark, layer, or other typed identifiers at compile time —
 * following the same pattern as {@link MarkId}/{@link LayerId}/{@link ZoneId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record DrawingId(UUID value) {

    public DrawingId {
        if (value == null) {
            throw new IllegalArgumentException("DrawingId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code DrawingId} using a random UUID.
     *
     * @return a fresh {@code DrawingId}
     */
    public static DrawingId random() {
        return new DrawingId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code DrawingId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code DrawingId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static DrawingId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DrawingId value must not be null");
        }
        try {
            return new DrawingId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DrawingId value must be a valid UUID: " + value, e);
        }
    }
}
