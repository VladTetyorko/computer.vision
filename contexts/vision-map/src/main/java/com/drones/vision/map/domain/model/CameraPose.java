package com.drones.vision.map.domain.model;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.FixedCameraPose;
import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.kernel.UserId;

import java.time.Instant;

/**
 * The audited, asset-keyed, persisted pose of a stationary camera (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md
 * decision D4) — one row per {@link AssetId}. Wraps a value shaped exactly like kernel's {@link
 * FixedCameraPose} (the five pure geometric numbers {@code FixedCameraGeo} projects from) plus the
 * bookkeeping a control-plane record needs: which asset this is, where its tracks should be drawn,
 * how the pose was produced, and who last touched it. See {@link FixedCameraPose}'s own javadoc for
 * why the geometric core stays a separate, pure kernel type rather than growing these fields itself.
 *
 * <p>{@link #position()}'s own {@code altitudeMeters} is unused by the projection (mirroring {@link
 * FixedCameraPose#position()}) — height above the ground the camera looks at is {@link #aglMeters()},
 * kept a separate field on purpose.
 *
 * @param assetId       the asset this camera pose belongs to; one row per asset (upsert by this key)
 * @param position      the camera's fixed latitude/longitude
 * @param aglMeters     the camera's height above the ground it looks at, meters; must not be negative
 * @param yawDegrees    the compass bearing the boresight points along, clockwise from true north;
 *                      normalized to {@code [0,360)}
 * @param pitchDegrees  the boresight's depression below horizontal, degrees; must be within
 *                      {@code [-10,90]} — a slight upward tilt is legal, individual rays still face
 *                      the horizon guard at projection time
 * @param hfovDegrees   the horizontal field of view, degrees; must be within {@code (10,160)}
 * @param targetLayerId which {@link MapLayer} this camera's tracks publish to, or {@code null} to use
 *                      the deployment's COP layer (docs/plans/done/FIXED-CAMERA-GEO-PLAN.md D10)
 * @param source        how this pose was produced — hand-entered, or solved and then confirmed
 * @param rmsErrorPixels the calibration solve's residual, pixels, or {@code null} for a {@link
 *                       CameraPoseSource#MANUAL} pose (nothing was solved)
 * @param updatedAt     when this pose was last written
 * @param updatedBy     who last wrote it
 */
public record CameraPose(AssetId assetId, GeoPosition position, double aglMeters, double yawDegrees,
                          double pitchDegrees, double hfovDegrees, LayerId targetLayerId,
                          CameraPoseSource source, Double rmsErrorPixels, Instant updatedAt, UserId updatedBy) {

    public CameraPose {
        if (assetId == null) {
            throw new IllegalArgumentException("CameraPose assetId must not be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("CameraPose position must not be null");
        }
        if (Double.isNaN(aglMeters) || Double.isInfinite(aglMeters) || aglMeters < 0.0) {
            throw new IllegalArgumentException("CameraPose aglMeters must not be negative: " + aglMeters);
        }
        if (Double.isNaN(yawDegrees) || Double.isInfinite(yawDegrees)) {
            throw new IllegalArgumentException("CameraPose yawDegrees must be finite: " + yawDegrees);
        }
        yawDegrees = ((yawDegrees % 360.0) + 360.0) % 360.0;
        if (Double.isNaN(pitchDegrees) || pitchDegrees < -10.0 || pitchDegrees > 90.0) {
            throw new IllegalArgumentException("CameraPose pitchDegrees must be within [-10,90]: " + pitchDegrees);
        }
        if (Double.isNaN(hfovDegrees) || hfovDegrees <= 10.0 || hfovDegrees >= 160.0) {
            throw new IllegalArgumentException("CameraPose hfovDegrees must be within (10,160): " + hfovDegrees);
        }
        if (source == null) {
            throw new IllegalArgumentException("CameraPose source must not be null");
        }
        if (rmsErrorPixels != null && (Double.isNaN(rmsErrorPixels) || rmsErrorPixels < 0.0)) {
            throw new IllegalArgumentException("CameraPose rmsErrorPixels must not be negative: " + rmsErrorPixels);
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("CameraPose updatedAt must not be null");
        }
        if (updatedBy == null) {
            throw new IllegalArgumentException("CameraPose updatedBy must not be null");
        }
    }

    /**
     * The pure geometric core {@code FixedCameraGeo} projects from — this pose's five numbers, with
     * every asset/audit/persistence concern stripped away.
     *
     * @return an equivalent {@link FixedCameraPose}
     */
    public FixedCameraPose toFixedCameraPose() {
        return new FixedCameraPose(position, aglMeters, yawDegrees, pitchDegrees, hfovDegrees);
    }
}
