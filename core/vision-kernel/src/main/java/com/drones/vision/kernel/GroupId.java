package com.drones.vision.kernel;

import java.util.UUID;

/**
 * Typed identity for the group an {@link Asset}'s owner belongs to.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so group identities cannot be mixed up with user or other
 * typed identifiers at compile time, and can never hold a malformed value.
 * Groups form a tree in the full identity model (ARCHITECTURE.md §6); until
 * that phase a constant dev principal — a fixed {@code GroupId} wrapping a
 * well-known {@link UUID} — owns everything, applied in the application
 * layer and never hard-coded in the domain.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record GroupId(UUID value) {

    public GroupId {
        if (value == null) {
            throw new IllegalArgumentException("GroupId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code GroupId} using a random UUID.
     *
     * @return a fresh {@code GroupId}
     */
    public static GroupId random() {
        return new GroupId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code GroupId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code GroupId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static GroupId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("GroupId value must not be null");
        }
        try {
            return new GroupId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("GroupId value must be a valid UUID: " + value, e);
        }
    }
}
