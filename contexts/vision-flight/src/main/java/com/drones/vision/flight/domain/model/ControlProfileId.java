package com.drones.vision.flight.domain.model;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Typed identity for a {@link ControlProfile}, following the {@link ZoneId}/{@code AssetId} pattern
 * so a profile id can never be passed where an asset or device id is expected.
 *
 * <p>{@link #builtIn(VehicleKind)} is <b>derived, not random</b>: the built-in profile for a vehicle
 * kind must present the same identity on every server and across every restart, because a browser
 * that saved "I was flying with the built-in rover profile" has to still mean something tomorrow. A
 * random id per JVM would make the built-ins un-referenceable, and storing them in the database
 * would make {@link ControlProfile#forKind}'s totality depend on a migration having run.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record ControlProfileId(UUID value) {

    /** Namespace prefix for {@link #builtIn}'s derived ids — never parsed back, only hashed. */
    private static final String BUILT_IN_NAMESPACE = "vision:control-profile:built-in:";

    public ControlProfileId {
        if (value == null) {
            throw new IllegalArgumentException("ControlProfileId value must not be null");
        }
    }

    /**
     * Generates a fresh identity for a profile an operator is creating.
     *
     * @return a random {@code ControlProfileId}
     */
    public static ControlProfileId random() {
        return new ControlProfileId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (an API path variable, a JSON field).
     *
     * @param value canonical UUID string
     * @return the parsed id
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID
     */
    public static ControlProfileId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ControlProfileId value must not be null");
        }
        try {
            return new ControlProfileId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ControlProfileId value must be a valid UUID: " + value, e);
        }
    }

    /**
     * The stable identity of the built-in profile for one vehicle kind — the same UUID on every
     * server, forever.
     *
     * @param kind the vehicle kind
     * @return that kind's built-in profile id
     * @throws IllegalArgumentException if {@code kind} is {@code null}
     */
    public static ControlProfileId builtIn(VehicleKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("ControlProfileId kind must not be null");
        }
        return new ControlProfileId(
                UUID.nameUUIDFromBytes((BUILT_IN_NAMESPACE + kind.name()).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Whether this id names a built-in profile rather than a saved one — the check the application
     * layer gates "you may not edit or delete this" on.
     *
     * @return {@code true} if this equals {@link #builtIn(VehicleKind)} for any kind
     */
    public boolean isBuiltIn() {
        return builtInKind().isPresent();
    }

    /**
     * The vehicle kind this id is the built-in profile <em>of</em>, or empty when it names a saved
     * profile.
     *
     * <p>Exists because "activate the built-in" has to know which kind's active flag to clear — the
     * built-in itself is never a stored row to point at, so the kind has to come back out of the id
     * that was derived from it.
     */
    public Optional<VehicleKind> builtInKind() {
        for (VehicleKind kind : VehicleKind.values()) {
            if (builtIn(kind).equals(this)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
