package com.drones.vision.kernel;

/**
 * The fixed geometry of a stationary camera — where it sits, which way it points, and how wide it
 * sees. {@link FixedCameraGeo} turns a pixel inside this camera's frame into a ground point using
 * exactly these five numbers; nothing else about the camera (its owning asset, who last aimed it,
 * whether the pose was measured or solved) belongs at this layer. That bookkeeping is
 * {@code contexts/vision-map}'s {@code CameraPose} (docs/plans/active/FIXED-CAMERA-GEO-PLAN.md
 * D4), which carries a value shaped like this one plus audit metadata — this record is the pure
 * geometric core it wraps, not a competing concept.
 *
 * <p>{@code position}'s own {@code altitudeMeters} is not read by the projection — height above
 * the ground the camera looks at is {@code aglMeters}, the same split {@link GeoProjection#project}
 * already uses between a drone's ground position and its AGL altitude.
 *
 * @param position     the camera's fixed latitude/longitude
 * @param aglMeters    the camera's height above the ground it looks at, meters; must not be
 *                     negative
 * @param yawDegrees   the compass bearing the boresight points along, clockwise from true north;
 *                     any finite value (wrapping happens where it matters, in
 *                     {@link GeoProjection#project})
 * @param pitchDegrees the boresight's depression below horizontal, degrees; positive tilts the
 *                     camera down. Must be finite — a fixed camera may point slightly up
 * @param hfovDegrees  the horizontal field of view, degrees; must be within {@code (0,180)} so
 *                     {@code tan(hfov/2)} stays defined
 */
public record FixedCameraPose(GeoPosition position, double aglMeters, double yawDegrees,
                               double pitchDegrees, double hfovDegrees) {

    public FixedCameraPose {
        if (position == null) {
            throw new IllegalArgumentException("FixedCameraPose position must not be null");
        }
        if (Double.isNaN(aglMeters) || Double.isInfinite(aglMeters) || aglMeters < 0.0) {
            throw new IllegalArgumentException("FixedCameraPose aglMeters must not be negative: " + aglMeters);
        }
        if (Double.isNaN(yawDegrees) || Double.isInfinite(yawDegrees)) {
            throw new IllegalArgumentException("FixedCameraPose yawDegrees must be finite: " + yawDegrees);
        }
        if (Double.isNaN(pitchDegrees) || Double.isInfinite(pitchDegrees)) {
            throw new IllegalArgumentException("FixedCameraPose pitchDegrees must be finite: " + pitchDegrees);
        }
        if (Double.isNaN(hfovDegrees) || Double.isInfinite(hfovDegrees) || hfovDegrees <= 0.0 || hfovDegrees >= 180.0) {
            throw new IllegalArgumentException(
                    "FixedCameraPose hfovDegrees must be within (0,180): " + hfovDegrees);
        }
    }
}
