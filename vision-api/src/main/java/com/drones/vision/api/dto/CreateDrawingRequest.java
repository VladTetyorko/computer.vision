package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.application.map.DrawingSpec;
import com.drones.vision.map.domain.model.DrawKind;
import com.drones.vision.kernel.GeoPosition;

import java.util.List;

/**
 * Request body for {@code POST /api/map/drawings} (docs/plans/done/MAP-REWORK-PLAN.md §4.2).
 *
 * <p>Point-count rules per {@code kind} ({@code LINE}/{@code ARROW} ≥ 2, {@code POLYGON} ≥ 3,
 * {@code TEXT} exactly 1 with a non-blank label) are left to {@link DrawingSpec}'s own compact
 * constructor to report, mirroring how {@code GeofenceZoneRequest} lets {@code GeofenceZoneSpec}
 * validate its polygon — one rule, one place, one message.
 *
 * @param layerId    which layer to draw on, or absent for the caller's default layer
 * @param kind       {@code LINE}/{@code POLYGON}/{@code ARROW}/{@code TEXT}
 * @param label      free text; required for {@code TEXT}, optional otherwise
 * @param colorToken a design-system token name (kebab-case, ≤ 30 chars), never a hex value
 * @param points     the geometry, in draw order
 */
public record CreateDrawingRequest(String layerId, String kind, String label, String colorToken,
                                    List<PositionDto> points) {

    /**
     * Converts this request to the application-layer spec.
     *
     * @return the equivalent {@link DrawingSpec}
     * @throws IllegalArgumentException if {@code layerId} is malformed, {@code kind} is
     *                                   missing/unrecognized, a point is out of range, or the
     *                                   point count/label does not satisfy {@code kind} (→ 400)
     */
    public DrawingSpec toSpec() {
        return new DrawingSpec(
                MapRequests.optionalLayerId(layerId),
                EnumParsing.require(DrawKind.class, "kind", kind),
                toPoints(points),
                label,
                colorToken);
    }

    /**
     * @param points the wire geometry, or {@code null}
     * @return the domain geometry; an absent list becomes empty so {@code DrawingSpec}/{@code
     *         DrawingPatch} reports the real "wrong number of points for this kind" message rather
     *         than a bare NPE
     */
    static List<GeoPosition> toPoints(List<PositionDto> points) {
        return points == null ? List.of() : points.stream().map(PositionDto::toPosition).toList();
    }
}
