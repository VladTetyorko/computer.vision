package com.drones.vision.api.dto;

import com.drones.vision.kernel.GeoPosition;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A single point on the map, both inbound and outbound (docs/plans/done/MAP-REWORK-PLAN.md §4.2) — the
 * geometry element of {@code CreateDrawingRequest}/{@code PatchDrawingRequest}/{@link
 * DrawingResponse}.
 *
 * <p>Deliberately a separate type from {@link GeoPositionResponse}, which is
 * response-only and predates the map rework: this one is also parsed from a request body, so it
 * needs {@link #toPosition()} and must tolerate an absent {@code altitudeMeters}. Keeping them
 * apart avoids giving the older, read-only DTO a request-side responsibility it never had.
 *
 * @param latitude       degrees, {@code [-90,90]}
 * @param longitude      degrees, {@code [-180,180]}
 * @param altitudeMeters metres, or absent/{@code null} if the point carries no altitude
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PositionDto(double latitude, double longitude, Double altitudeMeters) {

    /**
     * @param position the position to map, or {@code null}
     * @return the wire form of {@code position}, or {@code null} if it was {@code null}
     */
    public static PositionDto from(GeoPosition position) {
        return position == null ? null
                : new PositionDto(position.latitude(), position.longitude(), position.altitudeMeters());
    }

    /**
     * @return this point as a domain {@link GeoPosition}
     * @throws IllegalArgumentException if latitude/longitude are out of range (→ 400)
     */
    public GeoPosition toPosition() {
        return new GeoPosition(latitude, longitude, altitudeMeters);
    }
}
