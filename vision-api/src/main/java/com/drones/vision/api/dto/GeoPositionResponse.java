package com.drones.vision.api.dto;

import com.drones.vision.domain.model.GeoPosition;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wire representation of a {@link GeoPosition}, embedded in {@code
 * AssetSummaryResponse}/{@code AssetDetailsResponse} (last known position)
 * and {@code AssetUsageResponse} (start/last position).
 *
 * <p>{@code altitudeMeters} is omitted from the JSON entirely (rather than
 * serialized as {@code null}) when the position carries no altitude reading.
 *
 * @param latitude       degrees
 * @param longitude      degrees
 * @param altitudeMeters meters, or absent if unknown
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GeoPositionResponse(double latitude, double longitude, Double altitudeMeters) {

    /**
     * Maps a domain {@link GeoPosition} to its wire representation.
     *
     * @param position the position to map, or {@code null}
     * @return the response body for {@code position}, or {@code null} if {@code position} is {@code null}
     */
    public static GeoPositionResponse from(GeoPosition position) {
        return position == null ? null
                : new GeoPositionResponse(position.latitude(), position.longitude(), position.altitudeMeters());
    }
}
