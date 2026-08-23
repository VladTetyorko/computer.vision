package com.drones.vision.api.dto;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.application.track.CalibrationLandmark;
import com.drones.vision.map.application.track.CalibrationRequest;

import java.util.List;

/**
 * Request body for {@code POST /api/assets/{assetId}/camera-pose/calibration}
 * (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5/D5) — solves but <b>never persists</b>; the
 * operator reviews the result and confirms with a separate {@code PUT}.
 *
 * <p>2–8 {@code points} (→ 400 outside that range) and a {@code u}/{@code v} outside {@code [0,1]}
 * (→ 400) are both enforced by {@link CalibrationRequest}'s/{@link CalibrationLandmark}'s own
 * compact constructors once {@link #toRequest()} builds them — not duplicated here, the same
 * "let the domain type be the single source of truth" idiom {@link PutCameraPoseRequest} follows.
 *
 * @param latitude    the camera's fixed latitude, operator-measured
 * @param longitude   the camera's fixed longitude, operator-measured
 * @param aglMeters   the camera's height above the ground it looks at, operator-measured
 * @param imageWidth  the calibration image's width, pixels
 * @param imageHeight the calibration image's height, pixels
 * @param points      the clicked correspondences; between 2 and 8 inclusive
 */
public record CalibrateCameraPoseRequest(double latitude, double longitude, double aglMeters, int imageWidth,
                                          int imageHeight, List<CalibrationPointRequest> points) {

    /**
     * Converts this request to the application-layer input.
     *
     * @return the equivalent {@link CalibrationRequest}
     * @throws IllegalArgumentException if {@code points} is outside {@code [2,8]}, any point's
     *                                   {@code u}/{@code v} is outside {@code [0,1]}, or {@code
     *                                   imageWidth}/{@code imageHeight} is not positive (→ 400)
     */
    public CalibrationRequest toRequest() {
        GeoPosition position = new GeoPosition(latitude, longitude, null);
        List<CalibrationLandmark> landmarks = points == null ? List.of()
                : points.stream().map(CalibrationPointRequest::toLandmark).toList();
        return new CalibrationRequest(position, aglMeters, imageWidth, imageHeight, landmarks);
    }
}
