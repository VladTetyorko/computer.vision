package com.drones.vision.kernel;

import java.io.Serializable;
import java.util.UUID;

/**
 * Typed identity for the user that owns an {@link Asset}.
 *
 * <p>Modeled as a value record wrapping {@link UUID} rather than a bare
 * {@code String} so user identities cannot be mixed up with group or other
 * typed identifiers at compile time, and can never hold a malformed value.
 * Full accounts/roles land in a later phase (see ARCHITECTURE.md §6); until
 * then a constant dev principal — a fixed {@code UserId} wrapping a
 * well-known {@link UUID} — owns everything, applied in the application
 * layer and never hard-coded in the domain.
 *
 * <p>{@link Serializable} (docs/plans/active/AUTH-ROLES-PLAN.md B5-fix): reachable from the
 * {@code VisionUserDetails} principal (station/vision-app) that Spring Session JDBC
 * java-serializes into {@code spring_session_attributes} — {@code java.io} is JDK, not a
 * third-party dependency, so this costs nothing against the module's own dependency rule.
 *
 * @param value the underlying identity; must not be {@code null}
 */
public record UserId(UUID value) implements Serializable {

    public UserId {
        if (value == null) {
            throw new IllegalArgumentException("UserId value must not be null");
        }
    }

    /**
     * Generates a new, effectively-unique {@code UserId} using a random UUID.
     *
     * @return a fresh {@code UserId}
     */
    public static UserId random() {
        return new UserId(UUID.randomUUID());
    }

    /**
     * Parses the canonical string form of a UUID (e.g. an API path variable
     * or JSON field) into a {@code UserId}.
     *
     * @param value canonical UUID string
     * @return the parsed {@code UserId}
     * @throws IllegalArgumentException if {@code value} is {@code null} or not a valid UUID string
     */
    public static UserId of(String value) {
        if (value == null) {
            throw new IllegalArgumentException("UserId value must not be null");
        }
        try {
            return new UserId(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("UserId value must be a valid UUID: " + value, e);
        }
    }
}
