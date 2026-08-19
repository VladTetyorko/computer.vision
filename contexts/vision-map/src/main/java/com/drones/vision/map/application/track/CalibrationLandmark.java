package com.drones.vision.map.application.track;

import com.drones.vision.kernel.GeoPosition;

/**
 * One clicked correspondence a calibration solve is built from (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md decision D5): a normalized pixel {@code (u,v)} in the calibration image,
 * paired with the map position the operator clicked for the same real-world point.
 *
 * @param u           normalized horizontal pixel coordinate, top-left origin; within {@code [0,1]}
 * @param v           normalized vertical pixel coordinate, top-left origin (so a bounding box's
 *                    bottom edge is a larger {@code v}, matching {@code FixedCameraGeo}'s own
 *                    convention); within {@code [0,1]}
 * @param mapPosition the map position clicked for this same landmark; {@code altitudeMeters} is
 *                    unused (flat-ground assumption, D5)
 */
public record CalibrationLandmark(double u, double v, GeoPosition mapPosition) {

    public CalibrationLandmark {
        if (Double.isNaN(u) || u < 0.0 || u > 1.0) {
            throw new IllegalArgumentException("CalibrationLandmark u must be within [0,1]: " + u);
        }
        if (Double.isNaN(v) || v < 0.0 || v > 1.0) {
            throw new IllegalArgumentException("CalibrationLandmark v must be within [0,1]: " + v);
        }
        if (mapPosition == null) {
            throw new IllegalArgumentException("CalibrationLandmark mapPosition must not be null");
        }
    }
}
