package com.drones.vision.warehouse.domain.model;

import java.util.UUID;

/**
 * Typed identity for an {@link AssetNote}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so note
 * identities cannot be mixed up with asset, device, or other typed identifiers at compile time,
 * and can never hold a malformed value — following the same pattern as {@code
 * com.drones.vision.kernel.AssetId}. Warehouse-local: notes are a fleet-management detail no other
 * context reads.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record NoteId(UUID value) {

    public NoteId {
        if (value == null) {
            throw new IllegalArgumentException("NoteId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code NoteId} using a random UUID.
     *
     * @return a fresh {@code NoteId}
     */
    public static NoteId random() {
        return new NoteId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code NoteId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code NoteId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static NoteId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("NoteId value must not be null");
        }
        try {
            return new NoteId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("NoteId value must be a valid UUID: " + value, e);
        }
    }
}
