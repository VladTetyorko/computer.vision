package com.drones.vision.warehouse.domain.model;

import java.util.UUID;

/**
 * Typed identity for a {@link MaintenanceRecord}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare {@code String} so
 * maintenance-record identities cannot be mixed up with asset, device, or other typed identifiers
 * at compile time, and can never hold a malformed value — following the same pattern as {@code
 * com.drones.vision.kernel.AssetId}. Warehouse-local (not in {@code vision-kernel}): no other
 * context needs to name a maintenance record directly, only ask whether one is blocking flight
 * (see {@code MaintenanceQuery}).
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record MaintenanceId(UUID value) {

    public MaintenanceId {
        if (value == null) {
            throw new IllegalArgumentException("MaintenanceId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code MaintenanceId} using a random UUID.
     *
     * @return a fresh {@code MaintenanceId}
     */
    public static MaintenanceId random() {
        return new MaintenanceId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable or JSON field) into a
     * {@code MaintenanceId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code MaintenanceId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static MaintenanceId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("MaintenanceId value must not be null");
        }
        try {
            return new MaintenanceId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MaintenanceId value must be a valid UUID: " + value, e);
        }
    }
}
