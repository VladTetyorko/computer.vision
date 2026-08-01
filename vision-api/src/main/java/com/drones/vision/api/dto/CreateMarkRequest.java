package com.drones.vision.api.dto;

import com.drones.vision.application.MarkSpec;
import com.drones.vision.domain.model.GeoPosition;
import com.drones.vision.domain.model.MarkKind;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Request body for {@code POST /api/marks} (docs/TACTICAL-MARKS-PLAN.md §4's frozen wire
 * contract) — a manual mark, dropped by a map click.
 *
 * <p>{@code kind} is matched case-insensitively against {@link MarkKind} names — same idiom as
 * {@link com.drones.vision.api.dto.GeofenceZoneRequest}/{@link SetLifecycleStateRequest} — throwing
 * {@link IllegalArgumentException} (→400 via {@link com.drones.vision.api.ApiExceptionHandler}) for
 * an unrecognized value, listing the valid ones. {@code position} is required; a missing one is
 * left to {@link MarkSpec}'s own compact-constructor null check to report, mirroring how {@link
 * com.drones.vision.api.dto.GeofenceZoneRequest#toSpec()} lets {@code GeofenceZoneSpec} validate
 * its own polygon.
 *
 * @param kind     {@code "TARGET"}, {@code "HAZARD"}, {@code "POI"}, or {@code "FRIENDLY"}, matched
 *                 case-insensitively
 * @param label    short human-readable label; must not be blank
 * @param note     optional free-text detail; blank normalizes to {@code null}
 * @param position where to drop the mark
 */
public record CreateMarkRequest(String kind, String label, String note, PointRequest position) {

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link MarkSpec}
     * @throws IllegalArgumentException if {@code kind} is missing/unrecognized, or any field fails
     *                                   {@link MarkSpec}'s own validation (e.g. a blank label or a
     *                                   missing position)
     */
    public MarkSpec toSpec() {
        return new MarkSpec(toKind(kind), label, note, position == null ? null : position.toPosition());
    }

    static MarkKind toKind(String kind) {
        if (kind != null) {
            for (MarkKind candidate : MarkKind.values()) {
                if (candidate.name().equalsIgnoreCase(kind)) {
                    return candidate;
                }
            }
        }
        throw new IllegalArgumentException("Unknown kind: " + kind + " (valid values: "
                + Arrays.stream(MarkKind.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }

    /**
     * One geo point on the wire — mirrors {@link com.drones.vision.api.dto.GeofenceZoneRequest
     * .PolygonPointRequest}'s nested-request convention, but (unlike a polygon vertex) carries its
     * own optional altitude, since a mark's position is a standalone {@link GeoPosition}, not one
     * vertex of a zone whose altitude ceiling is a separate, zone-level field. Also reused by {@link
     * PatchMarkRequest} for the drag-to-correct {@code position} field.
     *
     * @param latitude       degrees, range-validated by {@link GeoPosition}'s own compact constructor
     * @param longitude      degrees, range-validated by {@link GeoPosition}'s own compact constructor
     * @param altitudeMeters meters, or {@code null} if unknown
     */
    public record PointRequest(double latitude, double longitude, Double altitudeMeters) {

        GeoPosition toPosition() {
            return new GeoPosition(latitude, longitude, altitudeMeters);
        }
    }
}
