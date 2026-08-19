package com.drones.vision.api.dto;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.application.track.CalibrationLandmark;

/**
 * One clicked correspondence inside a {@link CalibrateCameraPoseRequest} body
 * (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5/D5) — a normalized frame pixel {@code (u,v)}
 * paired with the map position clicked for the same real-world point.
 *
 * @param u         normalized horizontal pixel coordinate, top-left origin; within {@code [0,1]}
 * @param v         normalized vertical pixel coordinate, top-left origin; within {@code [0,1]}
 * @param latitude  the map position clicked for this landmark
 * @param longitude the map position clicked for this landmark
 */
public record CalibrationPointRequest(double u, double v, double latitude, double longitude) {

    /**
     * Converts this request to the application-layer landmark.
     *
     * @return the equivalent {@link CalibrationLandmark}
     * @throws IllegalArgumentException if {@code u}/{@code v} is outside {@code [0,1]} (→ 400)
     */
    public CalibrationLandmark toLandmark() {
        return new CalibrationLandmark(u, v, new GeoPosition(latitude, longitude, null));
    }
}
