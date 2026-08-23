package com.drones.vision.api.dto;

import com.drones.vision.map.domain.model.TrackPoint;

import java.time.Instant;

/**
 * One stored trail point inside {@link ProjectedTrackResponse}'s {@code trail} array
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5) — oldest to newest, already decimated
 * server-side (D7). Unlike the live {@link TrackPoint} domain record, this wire shape carries no
 * {@code errorRadiusMeters}/{@code label}/{@code layerId} — §5 freezes the trail entry as bare
 * {@code {latitude, longitude, at}}, the three fields a rendered polyline needs.
 *
 * @param latitude  the projected ground point's latitude
 * @param longitude the projected ground point's longitude
 * @param at        when this point was captured
 */
public record TrackPointResponse(double latitude, double longitude, Instant at) {

    /**
     * Maps a domain {@link TrackPoint} to its wire representation.
     *
     * @param point the stored trail point
     * @return the response entry for {@code point}
     */
    public static TrackPointResponse from(TrackPoint point) {
        return new TrackPointResponse(point.position().latitude(), point.position().longitude(), point.capturedAt());
    }
}
