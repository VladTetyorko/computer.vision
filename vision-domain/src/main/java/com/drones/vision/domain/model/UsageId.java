package com.drones.vision.domain.model;

import java.util.UUID;

/**
 * Typed identity for an {@link AssetUsage}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so usage identities cannot be mixed up with asset, device,
 * or other typed identifiers at compile time, and can never hold a
 * malformed value — following the same pattern as {@link DeviceId} and
 * {@link StreamId}. Telemetry samples are keyed by this id via {@code
 * TelemetryRepositoryPort}.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record UsageId(UUID value) {

    public UsageId {
        if (value == null) {
            throw new IllegalArgumentException("UsageId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code UsageId} using a random UUID.
     *
     * @return a fresh {@code UsageId}
     */
    public static UsageId random() {
        return new UsageId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code UsageId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code UsageId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static UsageId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("UsageId value must not be null");
        }
        try {
            return new UsageId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("UsageId value must be a valid UUID: " + value, e);
        }
    }
}
