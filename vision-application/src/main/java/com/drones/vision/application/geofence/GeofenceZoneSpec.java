package com.drones.vision.application.geofence;

import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.ZoneKind;

import java.util.List;
import com.drones.vision.application.asset.AssetSpec;
import com.drones.vision.application.device.DeviceRegistration;

/**
 * Everything needed to create or update a {@code GeofenceZone} (docs/OPS-CORE-PLAN.md §G) — one
 * shape for both operations, since the wire contract's create/update request bodies are
 * identical (the response's shape minus {@code id}).
 *
 * <p>A top-level record rather than a type nested in {@link GeofenceService}, so callers can name
 * their input without importing the service, and so the wire DTO in {@code …api.dto} maps to one
 * plain value — same reasoning as {@link DeviceRegistration}/{@link AssetSpec}.
 *
 * @param name              human-readable name; must not be blank
 * @param kind              {@link ZoneKind#KEEP_IN} or {@link ZoneKind#KEEP_OUT}
 * @param polygon           boundary vertices, at least 3, defensively copied — the same invariant
 *                          {@code GeofenceZone} itself enforces, duplicated here (mirroring {@link
 *                          AssetSpec}'s own "at least one device" duplication of {@code Asset}'s
 *                          invariant) so a malformed request fails fast with a spec-specific
 *                          message before any repository interaction
 * @param maxAltitudeMeters altitude ceiling in meters, or {@code null} for no ceiling; must not be
 *                          negative if present
 * @param enabled           whether the zone should participate in breach evaluation
 */
public record GeofenceZoneSpec(String name, ZoneKind kind, List<GeoPosition> polygon, Double maxAltitudeMeters,
                                boolean enabled) {

    private static final int MIN_POLYGON_VERTICES = 3;

    public GeofenceZoneSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("GeofenceZoneSpec name must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("GeofenceZoneSpec kind must not be null");
        }
        if (polygon == null) {
            throw new IllegalArgumentException("GeofenceZoneSpec polygon must not be null");
        }
        if (polygon.size() < MIN_POLYGON_VERTICES) {
            throw new IllegalArgumentException(
                    "GeofenceZoneSpec polygon must have at least " + MIN_POLYGON_VERTICES + " vertices: "
                            + polygon.size());
        }
        if (maxAltitudeMeters != null && maxAltitudeMeters < 0) {
            throw new IllegalArgumentException(
                    "GeofenceZoneSpec maxAltitudeMeters must not be negative: " + maxAltitudeMeters);
        }
        polygon = List.copyOf(polygon);
    }
}
