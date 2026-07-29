package com.drones.vision.api.dto;

import com.drones.vision.application.GeofenceZoneSpec;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.ZoneKind;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /api/geofences} and {@code PUT /api/geofences/{id}}
 * (docs/OPS-CORE-PLAN.md §G's frozen wire contract) — one shape for both create and update, since
 * the wire contract's bodies are identical (the response's shape minus {@code id}), mirroring
 * {@link GeofenceZoneSpec} (vision-application) itself.
 *
 * <p>{@code kind} is matched case-insensitively against {@link ZoneKind} names — same idiom as
 * {@link SetLifecycleStateRequest#toLifecycleState()}/{@link CapabilityParsing} — throwing {@link
 * IllegalArgumentException} (→400 via {@link com.drones.vision.api.ApiExceptionHandler}) for an
 * unrecognized value, listing the valid ones. A polygon with fewer than 3 vertices is **not**
 * checked here — that duplication belongs to {@link GeofenceZoneSpec}'s own compact constructor
 * (already enforced), so this DTO just maps shapes and lets the spec validate; {@code null}
 * inputs pass straight through to the same effect (its own null checks fire with a spec-specific
 * message).
 *
 * @param name              human-readable name; must not be blank
 * @param kind              {@code "KEEP_IN"} or {@code "KEEP_OUT"}, matched case-insensitively
 * @param polygon           boundary vertices, at least 3 — {@code {latitude, longitude}} only, no
 *                          per-vertex altitude (the zone-level {@code maxAltitudeMeters} is the
 *                          one altitude concept a zone carries)
 * @param maxAltitudeMeters altitude ceiling in meters, or {@code null} for no ceiling
 * @param enabled           whether the zone should participate in breach evaluation
 */
public record GeofenceZoneRequest(String name, String kind, List<PolygonPointRequest> polygon,
                                   Double maxAltitudeMeters, boolean enabled) {

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link GeofenceZoneSpec}
     * @throws IllegalArgumentException if {@code kind} is missing/unrecognized, or any field
     *                                   fails {@link GeofenceZoneSpec}'s own validation (e.g. a
     *                                   polygon with fewer than 3 vertices)
     */
    public GeofenceZoneSpec toSpec() {
        return new GeofenceZoneSpec(name, toKind(kind), toPolygon(polygon), maxAltitudeMeters, enabled);
    }

    private static ZoneKind toKind(String kind) {
        if (kind != null) {
            for (ZoneKind candidate : ZoneKind.values()) {
                if (candidate.name().equalsIgnoreCase(kind)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown kind: " + kind + " (valid values: "
                + Arrays.stream(ZoneKind.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }

    private static List<GeoPosition> toPolygon(List<PolygonPointRequest> polygon) {
        return polygon == null ? null : polygon.stream().map(PolygonPointRequest::toPosition).toList();
    }

    /**
     * One polygon vertex on the wire — latitude/longitude only, no altitude (the zone-level
     * {@code maxAltitudeMeters} is the one altitude concept a zone carries) — mirrors {@link
     * CreateAssetRequest.DeviceSpec}'s nested-request convention.
     *
     * @param latitude  degrees, range-validated by {@link GeoPosition}'s own compact constructor
     * @param longitude degrees, range-validated by {@link GeoPosition}'s own compact constructor
     */
    public record PolygonPointRequest(double latitude, double longitude) {

        GeoPosition toPosition() {
            return new GeoPosition(latitude, longitude, null);
        }
    }
}
