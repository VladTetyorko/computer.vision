package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.FixedCameraPose;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Wire representation of a {@link CameraPose} — the response body for {@code GET}/{@code PUT
 * /api/assets/{assetId}/camera-pose}, and the embedded {@code pose} field of a solved {@code
 * POST .../camera-pose/calibration} response (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md §5's
 * frozen wire contract).
 *
 * <p>Position is <em>flattened</em> to {@code latitude}/{@code longitude} rather than nested,
 * matching {@link MarkResponse}'s own shape — a pose's own {@code altitudeMeters} is deliberately
 * absent (unused by the projection, see {@link CameraPose}'s own javadoc), not merely omitted for
 * being {@code null}.
 *
 * @param assetId        the asset this pose belongs to, as a canonical UUID string
 * @param latitude       the camera's fixed latitude
 * @param longitude      the camera's fixed longitude
 * @param aglMeters      the camera's height above the ground it looks at, meters
 * @param yawDegrees     the compass bearing the boresight points along, normalized to {@code [0,360)}
 * @param pitchDegrees   the boresight's depression below horizontal, degrees
 * @param hfovDegrees    the horizontal field of view, degrees
 * @param targetLayerId  which layer this camera's tracks publish to, or absent for the COP layer
 * @param source         {@code MANUAL} or {@code CALIBRATED}
 * @param rmsErrorPixels the calibration solve's residual, or absent for a {@code MANUAL} pose
 * @param updatedAt      when this pose was last written (for a calibration preview never
 *                        persisted, the instant the solve ran)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CameraPoseResponse(String assetId, double latitude, double longitude, double aglMeters,
                                  double yawDegrees, double pitchDegrees, double hfovDegrees,
                                  String targetLayerId, String source, Double rmsErrorPixels, Instant updatedAt) {

    /**
     * Maps a persisted domain {@link CameraPose} to its wire representation.
     *
     * @param pose the stored pose
     * @return the response body for {@code pose}
     */
    public static CameraPoseResponse from(CameraPose pose) {
        return new CameraPoseResponse(
                pose.assetId().value().toString(),
                pose.position().latitude(),
                pose.position().longitude(),
                pose.aglMeters(),
                pose.yawDegrees(),
                pose.pitchDegrees(),
                pose.hfovDegrees(),
                pose.targetLayerId() == null ? null : pose.targetLayerId().value().toString(),
                pose.source().name(),
                pose.rmsErrorPixels(),
                pose.updatedAt());
    }

    /**
     * Maps a solved-but-never-persisted {@link FixedCameraPose} (the calibration solver's own
     * result) to the {@code pose} field of a {@code solved:true} {@code POST .../calibration}
     * response — {@code targetLayerId} is always absent here (no target layer has been assigned
     * yet) and {@code source} is always {@code CALIBRATED} (D5: this shape only exists on a solve).
     *
     * @param assetId        the asset the calibration was run for
     * @param pose           the solver's own five geometric numbers
     * @param rmsErrorPixels the fit's residual, pixels
     * @param solvedAt       when the solve ran — this preview is never persisted, so there is no
     *                       real {@code updatedAt} to report
     * @return a preview response, not backed by any stored row
     */
    public static CameraPoseResponse preview(AssetId assetId, FixedCameraPose pose, double rmsErrorPixels,
                                              Instant solvedAt) {
        return new CameraPoseResponse(
                assetId.value().toString(),
                pose.position().latitude(),
                pose.position().longitude(),
                pose.aglMeters(),
                pose.yawDegrees(),
                pose.pitchDegrees(),
                pose.hfovDegrees(),
                null,
                CameraPoseSource.CALIBRATED.name(),
                rmsErrorPixels,
                solvedAt);
    }
}
