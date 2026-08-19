package com.drones.vision.map.application.track;

import com.drones.vision.kernel.GeoPosition;
import com.drones.vision.map.domain.model.CameraPose;
import com.drones.vision.map.domain.model.CameraPoseSource;
import com.drones.vision.map.domain.model.LayerId;

/**
 * Everything needed to set an asset's {@link CameraPose} — the frozen {@code PUT
 * /api/assets/{assetId}/camera-pose} body (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md §5), used both
 * for manual entry and for confirming a calibration solve.
 *
 * <p>A top-level record rather than a type nested in {@link CameraPoseService}, so the wire DTO in
 * {@code …api.dto} maps to one plain value — same reasoning as {@link
 * com.drones.vision.map.application.mark.MarkSpec}.
 *
 * <p>Numeric ranges are <em>not</em> duplicated here — {@link CameraPoseService#put} builds a {@link
 * CameraPose} from this input and lets its own compact constructor be the single source of truth for
 * {@code aglMeters}/{@code pitchDegrees}/{@code hfovDegrees}/{@code yawDegrees} range validation,
 * mirroring how {@link com.drones.vision.map.application.mark.GeolocateSpec#depressionDegrees()}
 * leaves range validation to the kernel type it feeds.
 *
 * @param position       the camera's fixed latitude/longitude
 * @param aglMeters      the camera's height above the ground it looks at, meters
 * @param yawDegrees     the compass bearing the boresight points along
 * @param pitchDegrees   the boresight's depression below horizontal, degrees
 * @param hfovDegrees    the horizontal field of view, degrees
 * @param targetLayerId  which layer this camera's tracks publish to, or {@code null} for the COP layer
 * @param source         hand-entered, or solved and confirmed; must not be {@code null}
 * @param rmsErrorPixels the calibration solve's residual, or {@code null} for a manual pose
 */
public record CameraPoseInput(GeoPosition position, double aglMeters, double yawDegrees, double pitchDegrees,
                               double hfovDegrees, LayerId targetLayerId, CameraPoseSource source,
                               Double rmsErrorPixels) {

    public CameraPoseInput {
        if (position == null) {
            throw new IllegalArgumentException("CameraPoseInput position must not be null");
        }
        if (source == null) {
            throw new IllegalArgumentException("CameraPoseInput source must not be null");
        }
    }
}
