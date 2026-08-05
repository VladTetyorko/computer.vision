package com.drones.vision.domain.model;

import java.time.Instant;
import java.util.List;

/**
 * A named, access-controlled layer marks and drawings live on (docs/MAP-REWORK-PLAN.md §2.1) —
 * DELTA's "give N participants access to a layer" idea. {@link #kind()} sets the default
 * visibility/write baseline ({@link LayerKind}'s own javadoc); {@link #grants()} extends it to
 * specific users/groups beyond that baseline. Resolving effective access for a viewer is {@code
 * MapAccessPolicy}'s job (vision-application), not this record's.
 *
 * <p>Exactly one {@link LayerKind#COP} layer exists per deployment (the shared operational
 * picture every viewer sees) — that uniqueness is enforced by the service that creates/bootstraps
 * layers, not by this record in isolation.
 *
 * @param id        typed layer identity
 * @param name      human-readable name; must not be blank, at most {@value #MAX_NAME_LENGTH} chars
 * @param kind       {@link LayerKind#COP}, {@link LayerKind#TEAM}, or {@link LayerKind#PERSONAL}
 * @param ownership who created this layer and which group it belongs to
 * @param grants    explicit access grants beyond {@code kind}'s baseline; defensively copied,
 *                  never {@code null}, may be empty
 * @param createdAt when this layer was created; never changes afterward
 */
public record MapLayer(LayerId id, String name, LayerKind kind, Ownership ownership,
                        List<LayerGrant> grants, Instant createdAt) {

    /** A layer name longer than this is almost certainly a pasted description, not a name. */
    public static final int MAX_NAME_LENGTH = 80;

    public MapLayer {
        if (id == null) {
            throw new IllegalArgumentException("MapLayer id must not be null");
        }
        name = requireValidName(name);
        if (kind == null) {
            throw new IllegalArgumentException("MapLayer kind must not be null");
        }
        if (ownership == null) {
            throw new IllegalArgumentException("MapLayer ownership must not be null");
        }
        if (grants == null) {
            throw new IllegalArgumentException("MapLayer grants must not be null");
        }
        grants = List.copyOf(grants);
        if (createdAt == null) {
            throw new IllegalArgumentException("MapLayer createdAt must not be null");
        }
    }

    private static String requireValidName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("MapLayer name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "MapLayer name must be at most " + MAX_NAME_LENGTH + " characters: " + name.length());
        }
        return name;
    }

    /**
     * Returns a copy of this layer with a different name.
     *
     * @param name the replacement name; must not be blank, at most {@value #MAX_NAME_LENGTH} chars
     * @return a new {@code MapLayer} with {@code name} replaced
     */
    public MapLayer withName(String name) {
        return new MapLayer(id, name, kind, ownership, grants, createdAt);
    }

    /**
     * Returns a copy of this layer with a wholesale-replaced grant list.
     *
     * @param grants the replacement grants; defensively copied, must not be {@code null}
     * @return a new {@code MapLayer} with {@code grants} replaced
     */
    public MapLayer withGrants(List<LayerGrant> grants) {
        return new MapLayer(id, name, kind, ownership, grants, createdAt);
    }
}
