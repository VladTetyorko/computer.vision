package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link MapLayer}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so layer
 * identities cannot be mixed up with mark, drawing, or other typed identifiers at compile time —
 * following the same pattern as {@link MarkId}/{@link ZoneId}/{@link AssetId}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record LayerId(UUID value) {

    public LayerId {
        if (value == null) {
            throw new IllegalArgumentException("LayerId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code LayerId} using a random UUID.
     *
     * @return a fresh {@code LayerId}
     */
    public static LayerId random() {
        return new LayerId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code LayerId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code LayerId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static LayerId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("LayerId value must not be null");
        }
        try {
            return new LayerId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("LayerId value must be a valid UUID: " + value, e);
        }
    }
}
