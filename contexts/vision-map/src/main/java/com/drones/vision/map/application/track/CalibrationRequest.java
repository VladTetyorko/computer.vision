package com.drones.vision.map.application.track;

import com.drones.vision.kernel.GeoPosition;

import java.util.List;

/**
 * Everything {@link CameraCalibrationSolver#solve} needs — the frozen {@code POST
 * /api/assets/{assetId}/camera-pose/calibration} body (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md
 * §5, decision D5): the camera's operator-measured position and height (the solver does not solve
 * these), the calibration image's dimensions, and 2–8 clicked landmark correspondences.
 *
 * @param cameraPosition    the camera's fixed latitude/longitude, operator-measured
 * @param aglMeters         the camera's height above the ground it looks at, operator-measured;
 *                          must not be negative
 * @param imageWidthPixels  the calibration image's width; must be positive
 * @param imageHeightPixels the calibration image's height; must be positive
 * @param landmarks         the clicked correspondences; between {@link #MIN_LANDMARKS} and {@link
 *                          #MAX_LANDMARKS} inclusive (→400 outside that range)
 */
public record CalibrationRequest(GeoPosition cameraPosition, double aglMeters, int imageWidthPixels,
                                  int imageHeightPixels, List<CalibrationLandmark> landmarks) {

    /** Fewer than this many correspondences cannot constrain yaw/pitch/hfov at all (D5). */
    public static final int MIN_LANDMARKS = 2;

    /** More than this many is rejected outright — a wire-contract ceiling (§5), not a math one. */
    public static final int MAX_LANDMARKS = 8;

    public CalibrationRequest {
        if (cameraPosition == null) {
            throw new IllegalArgumentException("CalibrationRequest cameraPosition must not be null");
        }
        if (Double.isNaN(aglMeters) || Double.isInfinite(aglMeters) || aglMeters < 0.0) {
            throw new IllegalArgumentException("CalibrationRequest aglMeters must not be negative: " + aglMeters);
        }
        if (imageWidthPixels <= 0) {
            throw new IllegalArgumentException(
                    "CalibrationRequest imageWidthPixels must be positive: " + imageWidthPixels);
        }
        if (imageHeightPixels <= 0) {
            throw new IllegalArgumentException(
                    "CalibrationRequest imageHeightPixels must be positive: " + imageHeightPixels);
        }
        landmarks = landmarks == null ? List.of() : List.copyOf(landmarks);
        if (landmarks.size() < MIN_LANDMARKS || landmarks.size() > MAX_LANDMARKS) {
            throw new IllegalArgumentException("CalibrationRequest requires between " + MIN_LANDMARKS + " and "
                    + MAX_LANDMARKS + " landmarks, got: " + landmarks.size());
        }
    }
}
