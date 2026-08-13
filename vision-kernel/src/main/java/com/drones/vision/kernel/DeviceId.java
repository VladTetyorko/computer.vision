package com.drones.vision.kernel;

import java.util.UUID;

/**
 * Typed identity for a {@link Device}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so device identities cannot be mixed up with stream,
 * model, or other typed identifiers at compile time, and can never hold a
 * malformed value. Per the platform's scalability decisions, device (and
 * stream) identities are intended future sharding/partition keys: keeping
 * them as a distinct type today means a partitioning scheme can be
 * introduced later without touching call sites.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record DeviceId(UUID value) {

    public DeviceId {
        if (value == null) {
            throw new IllegalArgumentException("DeviceId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code DeviceId} using a random UUID.
     *
     * @return a fresh {@code DeviceId}
     */
    public static DeviceId random() {
        return new DeviceId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code DeviceId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code DeviceId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static DeviceId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("DeviceId value must not be null");
        }
        try {
            return new DeviceId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("DeviceId value must be a valid UUID: " + value, e);
        }
    }
}
