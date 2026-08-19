package com.drones.vision.api.dto;

import com.drones.vision.api.support.EnumParsing;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.application.track.CameraPoseInput;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.map.domain.model.LayerId;

/**
 * Request body for {@code PUT /api/assets/{assetId}/camera-pose} (docs/plans/active/
 * FIXED-CAMERA-GEO-PLAN.md §5's frozen wire contract) — manual entry, or confirming a calibration
 * solve. A whole-resource replace, not a partial patch: every field is sent every time, matching
 * {@link CreateMarkRequest}'s own flattened-position shape for the required six numbers.
 *
 * <p>Numeric ranges ({@code aglMeters ≥ 0}, {@code pitchDegrees ∈ [-10,90]}, {@code hfovDegrees ∈
 * (10,160)}, yaw finite) are <em>not</em> duplicated here — {@link #toInput()} builds a {@link
 * CameraPoseInput} and lets {@code CameraPoseService#put}'s own {@code CameraPose} compact
 * constructor be the single source of truth, exactly like {@link CameraPoseInput}'s own javadoc
 * documents.
 *
 * @param latitude       the camera's fixed latitude
 * @param longitude      the camera's fixed longitude
 * @param aglMeters      the camera's height above the ground it looks at, meters
 * @param yawDegrees     the compass bearing the boresight points along
 * @param pitchDegrees   the boresight's depression below horizontal, degrees
 * @param hfovDegrees    the horizontal field of view, degrees
 * @param targetLayerId  which layer this camera's tracks publish to; absent/blank resolves to the
 *                       COP layer
 * @param source         {@code MANUAL} or {@code CALIBRATED}, case-insensitive; required
 * @param rmsErrorPixels the calibration solve's residual, or absent for a manual pose
 */
public record PutCameraPoseRequest(double latitude, double longitude, double aglMeters, double yawDegrees,
                                    double pitchDegrees, double hfovDegrees, String targetLayerId, String source,
                                    Double rmsErrorPixels) {

    /**
     * Converts this request to the application-layer input.
     *
     * @return the equivalent {@link CameraPoseInput}
     * @throws IllegalArgumentException if {@code targetLayerId} is present but malformed, or {@code
     *                                   source} is missing/unrecognized (→ 400); numeric range
     *                                   failures surface later, from {@code CameraPoseService#put}
     */
    public CameraPoseInput toInput() {
        GeoPosition position = new GeoPosition(latitude, longitude, null);
        CameraPoseSource parsedSource = EnumParsing.require(CameraPoseSource.class, "source", source);
        LayerId layerId = MapRequests.optionalLayerId(targetLayerId);
        return new CameraPoseInput(position, aglMeters, yawDegrees, pitchDegrees, hfovDegrees, layerId,
                parsedSource, rmsErrorPixels);
    }
}
